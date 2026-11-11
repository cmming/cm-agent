package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalClientDevice;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 仅领取已由可信设备确认执行代次的项目任务。
 *
 * <p>该入口尚未连接设备协议。调用方必须先验证设备签名，并在同一事务完成派发审计；
 * 客户端还必须在执行前核对可信服务端和本地代次，数据库领取不能撤销已经发出的任务。</p>
 */
public final class JdbcClientProjectFencedTaskReader {
    private final JdbcClientProjectRunOwnershipRepository ownership;
    private final JdbcClientProjectFenceAcknowledgements acknowledgements;
    private final JdbcClient jdbc;
    private final String taskOwner;
    private final String fenceOwner;
    private final String ownershipOwner;
    private final String ackOwner;
    private final String bindingOwner;
    private final String clockQuery;
    private final UUID runtimeInstanceId;

    /** @param ownership 提供同一数据源和事务的可信归属仓储 */
    public JdbcClientProjectFencedTaskReader(JdbcClientProjectRunOwnershipRepository ownership) {
        this(ownership, null);
    }

    /**
     * @param ownership 提供同一数据源和事务的可信归属仓储
     * @param runtimeInstanceId 当前 Server 随机实例；为空仅供显式设备回报测试
     */
    public JdbcClientProjectFencedTaskReader(JdbcClientProjectRunOwnershipRepository ownership,
            UUID runtimeInstanceId) {
        this.ownership = Objects.requireNonNull(ownership);
        jdbc = ownership.jdbc();
        acknowledgements = new JdbcClientProjectFenceAcknowledgements(ownership);
        this.runtimeInstanceId = runtimeInstanceId;
        taskOwner = JdbcOwnerPredicate.equal(jdbc, "t.owner_id", ":owner");
        fenceOwner = JdbcOwnerPredicate.equal(jdbc, "f.owner_id", ":owner");
        ownershipOwner = JdbcOwnerPredicate.equal(jdbc, "o.owner_id", ":owner");
        ackOwner = JdbcOwnerPredicate.equal(jdbc, "a.owner_id", ":owner");
        bindingOwner = JdbcOwnerPredicate.equal(jdbc, "b.owner_id", ":owner");
        clockQuery = jdbc.sql("SELECT version()").query(String.class).single().startsWith("PostgreSQL")
                ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
    }

    /**
     * 在调用方事务内领取一条设备已确认的执行代次任务。
     * @param device 已验证签名的设备，数据库设备状态会重新核对
     * @return 条件领取成功的任务及其可信代次；无候选、竞争失败或归属变化时为空
     */
    public Optional<Claim> claim(LocalClientDevice device) {
        Objects.requireNonNull(device);
        ownership.requireTransaction();
        if (device.state() != LocalClientDevice.State.ACTIVE) return Optional.empty();
        Timestamp initialNow = jdbc.sql(clockQuery).query(Timestamp.class).single();
        // 候选查询不作为授权；后面按设备、归属、任务的固定顺序锁定后重新核验。
        var candidate = jdbc.sql("SELECT t.id,f.run_id,f.instance_id,f.epoch,o.phase FROM client_project_tasks t"
                + " JOIN client_project_task_fences f ON f.tenant_id=t.tenant_id AND f.task_id=t.id"
                + " AND f.run_id=t.run_id JOIN client_project_run_ownership o"
                + " ON o.tenant_id=f.tenant_id AND o.run_id=f.run_id AND o.epoch=f.epoch"
                + " AND o.instance_id=f.instance_id JOIN client_project_run_fence_acknowledgements a"
                + " ON a.tenant_id=f.tenant_id AND a.run_id=f.run_id AND a.epoch=f.epoch"
                + " AND a.device_id=t.device_id AND a.phase=o.phase"
                + " JOIN client_project_runs b ON b.tenant_id=t.tenant_id AND b.run_id=t.run_id"
                + " AND b.project_id=t.project_id AND b.agent_id=t.agent_id"
                + " AND b.conversation_id=t.conversation_id AND b.device_id=t.device_id"
                + " WHERE t.tenant_id=:tenant AND t.device_id=:device"
                + " AND t.state='PENDING' AND t.expires_at>:now"
                + " AND (o.phase='EXECUTING' OR o.phase='RECOVERING'"
                + " AND t.kind IN ('COMMAND_STATUS','STOP_COMMAND'))"
                + " AND o.expires_at>:now AND " + taskOwner + " AND " + fenceOwner
                + " AND " + ownershipOwner + " AND " + ackOwner + " AND " + bindingOwner
                + " ORDER BY t.created_at,t.id LIMIT 1")
                .param("tenant", device.tenantId().toString()).param("device", device.id().toString())
                .param("owner", device.ownerId()).param("now", initialNow)
                .query((row, index) -> new Candidate(UUID.fromString(row.getString("id")),
                        UUID.fromString(row.getString("run_id")), UUID.fromString(row.getString("instance_id")),
                        row.getLong("epoch"), ClientProjectRunOwnership.Phase.valueOf(row.getString("phase"))))
                .optional();
        if (candidate.isEmpty()) return Optional.empty();
        Candidate value = candidate.orElseThrow();
        if (!acknowledgements.lockConfirmed(device, value.runId(), value.instanceId(), value.epoch(),
                value.phase())) return Optional.empty();
        String owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        var locked = jdbc.sql("SELECT id FROM client_project_tasks WHERE tenant_id=:tenant AND id=:task"
                + " AND device_id=:device AND run_id=:run AND " + owner + " FOR UPDATE")
                .param("tenant", device.tenantId().toString()).param("task", value.taskId().toString())
                .param("device", device.id().toString()).param("run", value.runId().toString())
                .param("owner", device.ownerId()).query(String.class).optional();
        if (locked.isEmpty()) return Optional.empty();
        // 等待任务锁可能跨过租期；必须在锁到手后用数据库实际时间再次检查归属。
        if (value.phase() == ClientProjectRunOwnership.Phase.EXECUTING
                ? ownership.lockExecution(device.tenantId(), device.ownerId(), value.runId(),
                    value.instanceId(), value.epoch()).isEmpty()
                : ownership.lockRecovery(device.tenantId(), device.ownerId(), value.runId(),
                    value.instanceId(), value.epoch()).isEmpty()) return Optional.empty();
        var current = findTask(device, value.taskId()).orElseThrow();
        long validSource = jdbc.sql("SELECT COUNT(*) FROM client_project_runs b WHERE b.tenant_id=:tenant"
                + " AND b.run_id=:run AND b.device_id=:device AND b.project_id=:project"
                + " AND b.agent_id=:agent AND b.conversation_id=:conversation AND " + bindingOwner)
                .param("tenant", device.tenantId().toString()).param("run", value.runId().toString())
                .param("device", device.id().toString()).param("project", current.projectId().toString())
                .param("agent", current.agentId().toString()).param("conversation", current.conversationId().toString())
                .param("owner", device.ownerId()).query(Long.class).single();
        if (validSource != 1) return Optional.empty();
        Timestamp now = jdbc.sql(clockQuery).query(Timestamp.class).single();
        if (current.state() != ClientProjectTask.State.PENDING || !current.expiresAt().isAfter(now.toInstant()))
            return Optional.empty();
        int updated = jdbc.sql("UPDATE client_project_tasks SET state='DISPATCHED',claimed_at=:now,version=version+1"
                + " WHERE tenant_id=:tenant AND id=:task AND device_id=:device AND run_id=:run"
                + " AND state='PENDING' AND version=:version AND " + owner)
                .param("now", now).param("tenant", device.tenantId().toString())
                .param("task", value.taskId().toString()).param("device", device.id().toString())
                .param("run", value.runId().toString()).param("version", current.version())
                .param("owner", device.ownerId()).update();
        if (updated != 1) return Optional.empty();
        ClientProjectTask claimed = findTask(device, value.taskId()).orElseThrow();
        return Optional.of(new Claim(claimed, value.instanceId(), value.epoch(), value.phase()));
    }

    /**
     * 在调用方事务中对已验签设备的任务结果做代次、来源和条件版本复核。
     *
     * <p>调用方须在同一事务完成命令或文件意图状态推进及严格审计。未能确认的副作用不能
     * 因返回 false 而重试；租约失效或结果迟到应进入隔离对账。</p>
     * @param device 已验签设备，数据库状态仍会重核
     * @param taskId 请求中的任务标识，只能在该设备和租户范围内查找
     * @param expectedVersion 设备领取时收到并签名回报的任务版本
     * @param state 设备结果的成功、失败或未知状态
     * @param encryptedResult 经认证加密的结果，允许空值
     * @param safeError 调用方已脱敏的安全错误，允许空值
     * @return 条件更新成功时为 true；来源、代次、期限或版本不匹配时为 false
     */
    public boolean complete(LocalClientDevice device, UUID taskId, long expectedVersion,
            ClientProjectTask.State state, String encryptedResult, String safeError) {
        Objects.requireNonNull(device); Objects.requireNonNull(taskId); Objects.requireNonNull(state);
        ownership.requireTransaction();
        if (device.state() != LocalClientDevice.State.ACTIVE || expectedVersion < 1
                || state != ClientProjectTask.State.SUCCEEDED && state != ClientProjectTask.State.FAILED
                    && state != ClientProjectTask.State.UNKNOWN
                || safeError != null && safeError.length() > 500) return false;
        var candidate = jdbc.sql("SELECT t.id,f.run_id,f.instance_id,f.epoch,o.phase"
                + " FROM client_project_tasks t"
                + " JOIN client_project_task_fences f ON f.tenant_id=t.tenant_id AND f.task_id=t.id"
                + " AND f.run_id=t.run_id JOIN client_project_run_ownership o"
                + " ON o.tenant_id=f.tenant_id AND o.run_id=f.run_id AND o.epoch=f.epoch"
                + " AND o.instance_id=f.instance_id JOIN client_project_runs b"
                + " ON b.tenant_id=t.tenant_id AND b.run_id=t.run_id AND b.project_id=t.project_id"
                + " AND b.agent_id=t.agent_id AND b.conversation_id=t.conversation_id AND b.device_id=t.device_id"
                + " WHERE t.tenant_id=:tenant AND t.device_id=:device AND t.id=:task"
                + " AND t.state='DISPATCHED' AND t.version=:version"
                + " AND (o.phase='EXECUTING' OR o.phase='RECOVERING'"
                + " AND t.kind IN ('COMMAND_STATUS','STOP_COMMAND')) AND "
                + taskOwner + " AND " + fenceOwner + " AND " + bindingOwner)
                .param("tenant", device.tenantId().toString()).param("device", device.id().toString())
                .param("task", taskId.toString()).param("version", expectedVersion).param("owner", device.ownerId())
                .query((row, index) -> new Candidate(UUID.fromString(row.getString("id")),
                        UUID.fromString(row.getString("run_id")), UUID.fromString(row.getString("instance_id")),
                        row.getLong("epoch"), ClientProjectRunOwnership.Phase.valueOf(row.getString("phase"))))
                .optional();
        if (candidate.isEmpty()) return false;
        Candidate value = candidate.orElseThrow();
        if (!acknowledgements.lockConfirmed(device, value.runId(), value.instanceId(), value.epoch(),
                value.phase())) return false;
        String owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        var locked = jdbc.sql("SELECT id FROM client_project_tasks WHERE tenant_id=:tenant AND id=:task"
                + " AND device_id=:device AND run_id=:run AND " + owner + " FOR UPDATE")
                .param("tenant", device.tenantId().toString()).param("task", taskId.toString())
                .param("device", device.id().toString()).param("run", value.runId().toString())
                .param("owner", device.ownerId()).query(String.class).optional();
        if (locked.isEmpty() || (value.phase() == ClientProjectRunOwnership.Phase.EXECUTING
                ? ownership.lockExecution(device.tenantId(), device.ownerId(), value.runId(),
                    value.instanceId(), value.epoch()).isEmpty()
                : ownership.lockRecovery(device.tenantId(), device.ownerId(), value.runId(),
                    value.instanceId(), value.epoch()).isEmpty())) return false;
        Timestamp now = jdbc.sql(clockQuery).query(Timestamp.class).single();
        return jdbc.sql("UPDATE client_project_tasks SET state=:state,encrypted_result=:result,safe_error=:error,"
                + "completed_at=:now,version=version+1 WHERE tenant_id=:tenant AND id=:task AND device_id=:device"
                + " AND run_id=:run AND state='DISPATCHED' AND version=:version AND expires_at>:now AND " + owner
                + " AND EXISTS (SELECT 1 FROM client_project_runs b"
                + " WHERE b.tenant_id=client_project_tasks.tenant_id AND b.run_id=client_project_tasks.run_id"
                + " AND b.device_id=client_project_tasks.device_id AND b.project_id=client_project_tasks.project_id"
                + " AND b.agent_id=client_project_tasks.agent_id AND b.conversation_id=client_project_tasks.conversation_id"
                + " AND " + bindingOwner + ")")
                .param("state", state.name()).param("result", encryptedResult == null ? "" : encryptedResult)
                .param("error", safeError == null ? "" : safeError).param("now", now)
                .param("tenant", device.tenantId().toString()).param("task", taskId.toString())
                .param("device", device.id().toString()).param("run", value.runId().toString())
                .param("version", expectedVersion).param("owner", device.ownerId()).update() == 1;
    }

    /**
     * 判断任务是否固定了持久代次。
     *
     * @param tenantId 可信租户
     * @param taskId 项目任务标识
     * @return 存在栅栏记录时 true
     */
    public boolean isFenced(UUID tenantId, UUID taskId) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(taskId);
        return jdbc.sql("SELECT COUNT(*) FROM client_project_task_fences"
                        + " WHERE tenant_id=:tenant AND task_id=:task")
                .param("tenant", tenantId.toString()).param("task", taskId.toString())
                .query(Long.class).single() == 1;
    }

    /**
     * 当前执行实例在内部撤销未再交付的任务或收口已失效任务。
     *
     * <p>本入口不采用设备请求，也不替代签名结果回报；它只在数据库仍确认当前实例、当前代次和
     * 任务版本时推进。恢复阶段仅允许关闭状态/停止对账任务。</p>
     *
     * @param task 仓储读取的任务快照
     * @param state 失败、未知或取消状态
     * @param safeError 已脱敏原因
     * @param now 服务端收口时间
     * @return 条件关闭成功时 true
     */
    public boolean closeInternal(ClientProjectTask task, ClientProjectTask.State state,
            String safeError, Instant now) {
        Objects.requireNonNull(task); Objects.requireNonNull(state); Objects.requireNonNull(now);
        if (runtimeInstanceId == null || state != ClientProjectTask.State.FAILED
                && state != ClientProjectTask.State.UNKNOWN
                && state != ClientProjectTask.State.CANCELLED
                || safeError != null && safeError.length() > 500) return false;
        ownership.requireTransaction();
        var current = ownership.lockCurrentForInstance(task.tenantId(), task.ownerId(),
                task.runId(), runtimeInstanceId).orElse(null);
        if (current == null || !permits(task, current)) return false;
        String taskOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        return jdbc.sql("UPDATE client_project_tasks SET state=:state,safe_error=:error,"
                        + "completed_at=:now,version=version+1 WHERE tenant_id=:tenant AND id=:task"
                        + " AND device_id=:device AND run_id=:run AND state IN ('PENDING','DISPATCHED')"
                        + " AND version=:version AND " + taskOwner)
                .param("state", state.name()).param("error", safeError == null ? "" : safeError)
                .param("now", Timestamp.from(now))
                .param("tenant", task.tenantId().toString()).param("task", task.id().toString())
                .param("device", task.deviceId().toString()).param("run", task.runId().toString())
                .param("version", task.version()).param("owner", task.ownerId()).update() == 1;
    }

    private static boolean permits(ClientProjectTask task, ClientProjectRunOwnership current) {
        return current.phase() == ClientProjectRunOwnership.Phase.EXECUTING
                || current.phase() == ClientProjectRunOwnership.Phase.RECOVERING
                && (task.kind() == ClientProjectTask.Kind.COMMAND_STATUS
                    || task.kind() == ClientProjectTask.Kind.STOP_COMMAND);
    }

    private java.util.Optional<ClientProjectTask> findTask(LocalClientDevice device, UUID taskId) {
        String owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        return jdbc.sql("SELECT * FROM client_project_tasks WHERE tenant_id=:tenant AND id=:task"
                        + " AND device_id=:device AND " + owner)
                .param("tenant", device.tenantId().toString()).param("task", taskId.toString())
                .param("device", device.id().toString()).param("owner", device.ownerId())
                .query(this::mapTask).optional();
    }

    private ClientProjectTask mapTask(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        String projectId = row.getString("project_id");
        String runId = row.getString("run_id");
        String agentId = row.getString("agent_id");
        String conversationId = row.getString("conversation_id");
        String operationId = row.getString("operation_id");
        String commandId = row.getString("command_id");
        java.sql.Timestamp claimed = row.getTimestamp("claimed_at");
        java.sql.Timestamp completed = row.getTimestamp("completed_at");
        return new ClientProjectTask(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("device_id")),
                projectId == null ? null : UUID.fromString(projectId),
                runId == null ? null : UUID.fromString(runId),
                agentId == null ? null : UUID.fromString(agentId),
                conversationId == null ? null : UUID.fromString(conversationId),
                operationId == null ? null : UUID.fromString(operationId),
                commandId == null ? null : UUID.fromString(commandId),
                ClientProjectTask.Kind.valueOf(row.getString("kind")),
                row.getString("encrypted_request"),
                ClientProjectTask.State.valueOf(row.getString("state")),
                row.getString("encrypted_result"), row.getString("safe_error"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                claimed == null ? null : claimed.toInstant(),
                completed == null ? null : completed.toInstant(), row.getLong("version"));
    }

    /**
     * @param task 已锁定并条件领取的任务
     * @param instanceId 服务端可信实例
     * @param epoch 任务固定代次
     * @param phase 设备需核对的执行或受限恢复阶段
     */
    public record Claim(ClientProjectTask task, UUID instanceId, long epoch,
            ClientProjectRunOwnership.Phase phase) { }
    /** @param taskId 候选任务 @param runId 关联运行 @param instanceId 入队实例 @param epoch 入队代次 */
    private record Candidate(UUID taskId, UUID runId, UUID instanceId, long epoch,
            ClientProjectRunOwnership.Phase phase) { }
}
