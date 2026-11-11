package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 客户端项目任务队列。领取与终态推进都使用同一事务和条件版本，不把请求正文放入查询日志。
 */
public final class JdbcClientProjectTaskRepository implements ClientProjectTaskRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String owner;
    private final JdbcClientProjectFencedTaskWriter fencedWriter;
    private final JdbcClientProjectFencedTaskReader fencedReader;

    public JdbcClientProjectTaskRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this(jdbc, transaction, null, null);
    }

    /**
     * 配置生产任务仓储的持久代次边界。
     *
     * @param jdbc 项目任务数据访问入口
     * @param transaction 与同一数据源绑定的事务模板
     * @param ownership Run 归属仓储；为空只供旧仓储测试或迁移兼容
     * @param runtimeInstanceId 当前 Server 随机实例
     */
    public JdbcClientProjectTaskRepository(JdbcClient jdbc, TransactionTemplate transaction,
            JdbcClientProjectRunOwnershipRepository ownership, UUID runtimeInstanceId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.fencedWriter = ownership == null ? null
                : new JdbcClientProjectFencedTaskWriter(ownership, runtimeInstanceId);
        this.fencedReader = ownership == null ? null
                : new JdbcClientProjectFencedTaskReader(ownership, runtimeInstanceId);
    }

    @Override public boolean insert(ClientProjectTask task) {
        if (task.runId() != null && fencedWriter != null) return fencedWriter.insert(task);
        return insertRow(task);
    }

    @Override public boolean insertSelectionExclusive(ClientProjectTask task) {
        if (task.kind() != ClientProjectTask.Kind.SELECT_ROOT) {
            throw new IllegalArgumentException("独占插入仅用于目录选择任务");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            // 设备行锁把同一设备的并发选择请求串行化；任务表无需依赖数据库特有部分唯一索引。
            var locked = jdbc.sql("SELECT id FROM local_client_devices WHERE tenant_id=:tenant"
                    + " AND id=:device AND state='ACTIVE' AND "
                    + JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner") + " FOR UPDATE")
                    .param("tenant", task.tenantId().toString()).param("device", task.deviceId().toString())
                    .param("owner", task.ownerId()).query(String.class).optional();
            if (locked.isEmpty()) return false;
            long pending = jdbc.sql("SELECT COUNT(*) FROM client_project_tasks WHERE tenant_id=:tenant"
                    + " AND device_id=:device AND kind='SELECT_ROOT'"
                    + " AND state IN ('PENDING','DISPATCHED') AND "
                    + JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner"))
                    .param("tenant", task.tenantId().toString()).param("device", task.deviceId().toString())
                    .param("owner", task.ownerId()).query(Long.class).single();
            return pending == 0 && insertRow(task);
        }));
    }

    private boolean insertRow(ClientProjectTask task) {
        return jdbc.sql("""
                INSERT INTO client_project_tasks(id,tenant_id,owner_id,device_id,project_id,kind,
                  run_id,agent_id,conversation_id,operation_id,command_id,
                  encrypted_request,state,encrypted_result,safe_error,created_at,expires_at,
                  claimed_at,completed_at,version)
                VALUES (:id,:tenant,:owner,:device,:project,:kind,:run,:agent,:conversation,
                  :operation,:command,:request,:state,'','',:created,:expires,NULL,NULL,1)
                """).param("id", task.id().toString()).param("tenant", task.tenantId().toString())
                .param("owner", task.ownerId()).param("device", task.deviceId().toString())
                .param("project", task.projectId() == null ? null : task.projectId().toString())
                .param("run", task.runId() == null ? null : task.runId().toString())
                .param("agent", task.agentId() == null ? null : task.agentId().toString())
                .param("conversation", task.conversationId() == null ? null
                        : task.conversationId().toString())
                .param("operation", task.operationId() == null ? null
                        : task.operationId().toString())
                .param("command", task.commandId() == null ? null
                        : task.commandId().toString())
                .param("kind", task.kind().name()).param("request", task.encryptedRequest())
                .param("state", task.state().name()).param("created", Timestamp.from(task.createdAt()))
                .param("expires", Timestamp.from(task.expiresAt())).update() == 1;
    }

    @Override public Optional<ClientProjectTask> find(UUID tenantId, String ownerId,
            UUID deviceId, UUID taskId) {
        return jdbc.sql("SELECT * FROM client_project_tasks WHERE tenant_id=:tenant AND id=:id"
                + " AND device_id=:device AND " + owner)
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("device", deviceId.toString()).param("id", taskId.toString())
                .query(this::map).optional();
    }

    @Override public Optional<ClientProjectTask> findForOwner(UUID tenantId, String ownerId,
            UUID taskId) {
        return jdbc.sql("SELECT * FROM client_project_tasks WHERE tenant_id=:tenant AND id=:id AND " + owner)
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("id", taskId.toString()).query(this::map).optional();
    }

    @Override public boolean hasUnresolvedForProject(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND project_id=:project AND state IN ('PENDING','DISPATCHED','UNKNOWN') AND " + owner)
                .param("tenant", tenantId.toString()).param("project", projectId.toString())
                .param("owner", ownerId).query(Long.class).single() != 0;
    }

    @Override public boolean hasUnresolvedForCommand(UUID tenantId, String ownerId,
            UUID commandId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND command_id=:command AND state IN ('PENDING','DISPATCHED','UNKNOWN') AND "
                + owner)
                .param("tenant", tenantId.toString()).param("command", commandId.toString())
                .param("owner", ownerId).query(Long.class).single() != 0;
    }

    @Override public int closeOpenForCommand(UUID tenantId, String ownerId, UUID commandId,
            Instant now) {
        return jdbc.sql("UPDATE client_project_tasks SET state=CASE"
                + " WHEN state='PENDING' THEN 'CANCELLED' ELSE 'UNKNOWN' END,"
                + "completed_at=:now,version=version+1 WHERE tenant_id=:tenant"
                + " AND command_id=:command AND state IN ('PENDING','DISPATCHED') AND " + owner
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant)")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("command", commandId.toString()).param("owner", ownerId).update();
    }

    @Override public Optional<ClientProjectTask> claimNext(UUID tenantId, String ownerId,
            UUID deviceId, Instant now) {
        return transaction.execute(status -> {
            expirePending(tenantId, ownerId, deviceId, now);
            // 旧领取协议不携带代次，必须跳过有栅栏的任务，直到可信设备握手接入新入口。
            var task = jdbc.sql("SELECT t.* FROM client_project_tasks t WHERE t.tenant_id=:tenant"
                    + " AND t.device_id=:device AND t.state='PENDING' AND t.expires_at>:now AND "
                    + JdbcOwnerPredicate.equal(jdbc, "t.owner_id", ":owner")
                    + " AND NOT EXISTS (SELECT 1 FROM client_project_task_fences f"
                    + " WHERE f.tenant_id=t.tenant_id AND f.task_id=t.id)"
                    + " ORDER BY t.created_at,t.id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .param("tenant", tenantId.toString()).param("device", deviceId.toString())
                    .param("now", Timestamp.from(now)).param("owner", ownerId)
                    .query(this::map).optional();
            if (task.isEmpty()) return Optional.empty();
            var current = task.orElseThrow();
            int changed = jdbc.sql("UPDATE client_project_tasks SET state='DISPATCHED',claimed_at=:now,"
                    + "version=version+1 WHERE tenant_id=:tenant AND id=:id AND device_id=:device"
                    + " AND state='PENDING' AND version=:version AND " + owner)
                    .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                    .param("id", current.id().toString()).param("device", deviceId.toString())
                    .param("version", current.version()).param("owner", ownerId).update();
            return changed == 1 ? Optional.of(new ClientProjectTask(current.id(), current.tenantId(),
                    current.ownerId(), current.deviceId(), current.projectId(), current.runId(),
                    current.agentId(), current.conversationId(), current.operationId(),
                    current.commandId(),
                    current.kind(),
                    current.encryptedRequest(), ClientProjectTask.State.DISPATCHED, "", "",
                    current.createdAt(), current.expiresAt(), now, null, current.version() + 1))
                    : Optional.empty();
        });
    }

    @Override public boolean complete(UUID tenantId, String ownerId, UUID deviceId, UUID taskId,
            long expectedVersion, ClientProjectTask.State state, String encryptedResult,
            String safeError, Instant now) {
        if (state != ClientProjectTask.State.SUCCEEDED && state != ClientProjectTask.State.FAILED
                && state != ClientProjectTask.State.UNKNOWN) {
            throw new IllegalArgumentException("任务终态无效");
        }
        if (fencedReader != null && fencedReader.isFenced(tenantId, taskId)) {
            return closeForDeviceInternal(tenantId, ownerId, deviceId, taskId, expectedVersion,
                    state, safeError, now);
        }
        // 无栅栏的旧回执只能推进旧任务，不能借用新协议设备确认。
        return jdbc.sql("UPDATE client_project_tasks SET state=:state,encrypted_result=:result,"
                + "safe_error=:error,completed_at=:now,version=version+1 WHERE tenant_id=:tenant"
                + " AND id=:id AND device_id=:device AND state='DISPATCHED' AND version=:version AND " + owner
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant)")
                .param("state", state.name()).param("result", encryptedResult == null ? "" : encryptedResult)
                .param("error", safeError == null ? "" : safeError).param("now", Timestamp.from(now))
                .param("tenant", tenantId.toString()).param("id", taskId.toString())
                .param("device", deviceId.toString()).param("version", expectedVersion)
                .param("owner", ownerId).update() == 1;
    }

    private boolean closeForDeviceInternal(UUID tenantId, String ownerId, UUID deviceId,
            UUID taskId, long expectedVersion, ClientProjectTask.State state, String safeError,
            Instant now) {
        var task = find(tenantId, ownerId, deviceId, taskId).orElse(null);
        if (task == null || task.version() != expectedVersion) return false;
        return Boolean.TRUE.equals(transaction.execute(status ->
                fencedReader.closeInternal(task, state, safeError, now)));
    }

    @Override public boolean completeForDevice(com.cmagent.core.domain.LocalClientDevice device,
            UUID taskId, long expectedVersion, ClientProjectTask.State state, String encryptedResult,
            String safeError, Instant now) {
        Objects.requireNonNull(device);
        if (fencedReader != null && fencedReader.isFenced(device.tenantId(), taskId)) {
            return Boolean.TRUE.equals(transaction.execute(status -> fencedReader.complete(
                    device, taskId, expectedVersion, state, encryptedResult, safeError)));
        }
        return complete(device.tenantId(), device.ownerId(), device.id(), taskId,
                expectedVersion, state, encryptedResult, safeError, now);
    }

    @Override public boolean close(UUID tenantId, String ownerId, UUID deviceId, UUID taskId,
            long expectedVersion, ClientProjectTask.State state, String safeError, Instant now) {
        if (state != ClientProjectTask.State.FAILED && state != ClientProjectTask.State.UNKNOWN
                && state != ClientProjectTask.State.CANCELLED) {
            throw new IllegalArgumentException("任务关闭状态无效");
        }
        if (fencedReader != null && fencedReader.isFenced(tenantId, taskId)) return false;
        return jdbc.sql("UPDATE client_project_tasks SET state=:state,safe_error=:error,"
                + "completed_at=:now,version=version+1 WHERE tenant_id=:tenant AND id=:id"
                + " AND device_id=:device AND state IN ('PENDING','DISPATCHED')"
                + " AND version=:version AND " + owner
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant)")
                .param("state", state.name()).param("error", safeError == null ? "" : safeError)
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("id", taskId.toString()).param("device", deviceId.toString())
                .param("version", expectedVersion).param("owner", ownerId).update() == 1;
    }

    private void expirePending(UUID tenantId, String ownerId, UUID deviceId, Instant now) {
        jdbc.sql("UPDATE client_project_commands SET state='CANCELLED',version=version+1,"
                + "updated_at=:now WHERE tenant_id=:tenant AND state='QUEUED' AND " + owner
                + " AND id IN (SELECT command_id FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND device_id=:device AND kind='RUN_COMMAND' AND state='PENDING'"
                + " AND expires_at<=:now AND command_id IS NOT NULL"
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant) AND " + owner + ")")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("device", deviceId.toString()).param("owner", ownerId).update();
        jdbc.sql("UPDATE client_project_commands SET state='UNKNOWN',version=version+1,"
                + "updated_at=:now WHERE tenant_id=:tenant"
                + " AND state IN ('QUEUED','RUNNING','STOP_REQUESTED') AND " + owner
                + " AND id IN (SELECT command_id FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND device_id=:device AND kind IN ('COMMAND_STATUS','STOP_COMMAND')"
                + " AND state='PENDING' AND expires_at<=:now AND command_id IS NOT NULL AND "
                + owner + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant))")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("device", deviceId.toString()).param("owner", ownerId).update();
        jdbc.sql("UPDATE client_project_operations SET state='CANCELLED',version=version+1,"
                + "updated_at=:now WHERE tenant_id=:tenant AND state='APPLYING' AND " + owner
                + " AND id IN (SELECT operation_id FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND device_id=:device AND state='PENDING' AND expires_at<=:now"
                + " AND operation_id IS NOT NULL AND " + owner
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant))")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("device", deviceId.toString()).param("owner", ownerId).update();
        jdbc.sql("UPDATE client_project_tasks SET state='CANCELLED',completed_at=:now,version=version+1"
                + " WHERE tenant_id=:tenant AND device_id=:device AND state='PENDING'"
                + " AND expires_at<=:now AND " + owner
                + " AND id NOT IN (SELECT task_id FROM client_project_task_fences WHERE tenant_id=:tenant)")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("device", deviceId.toString()).param("owner", ownerId).update();
    }

    private ClientProjectTask map(ResultSet row, int index) throws SQLException {
        String projectId = row.getString("project_id");
        String runId = row.getString("run_id");
        String agentId = row.getString("agent_id");
        String conversationId = row.getString("conversation_id");
        String operationId = row.getString("operation_id");
        String commandId = row.getString("command_id");
        Timestamp claimed = row.getTimestamp("claimed_at");
        Timestamp completed = row.getTimestamp("completed_at");
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
}
