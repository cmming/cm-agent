package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.ClientProjectTask;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 在运行归属行锁内原子保存设备任务与固定代次。
 *
 * <p>本组件尚未替代既有派发入口，也不能阻止未使用本入口的旧任务领取；接线前还必须
 * 完成领取和客户端握手栅栏。调用方必须来自可信运行上下文，不能采用浏览器、模型或
 * 设备提交的实例与代次。任一插入失败由共同事务回滚，不留下无代次任务。</p>
 */
public final class JdbcClientProjectFencedTaskWriter {
    private final JdbcClientProjectRunOwnershipRepository ownership;
    private final JdbcClient jdbc;
    private final String sourceOwner;
    private final String deviceOwner;
    private final String clockQuery;
    private final UUID runtimeInstanceId;

    /** @param ownership 提供同一事务与数据库行锁的运行归属仓储 */
    public JdbcClientProjectFencedTaskWriter(JdbcClientProjectRunOwnershipRepository ownership) {
        this(ownership, null);
    }

    /**
     * @param ownership 提供同一事务与数据库行锁的运行归属仓储
     * @param runtimeInstanceId 当前 Server 随机实例；为空时只保留显式代次测试入口
     */
    public JdbcClientProjectFencedTaskWriter(JdbcClientProjectRunOwnershipRepository ownership,
            UUID runtimeInstanceId) {
        this.ownership = Objects.requireNonNull(ownership);
        this.jdbc = ownership.jdbc();
        this.runtimeInstanceId = runtimeInstanceId;
        this.sourceOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.deviceOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.clockQuery = jdbc.sql("SELECT version()").query(String.class).single().startsWith("PostgreSQL")
                ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
    }

    /**
     * 使用当前进程持有的最新可信代次保存任务。
     *
     * @param task 服务端形成的运行任务，必须属于当前实例仍持有的 Run
     * @return 归属、设备和来源仍有效且任务及代次原子插入成功时 true
     */
    public boolean insert(ClientProjectTask task) {
        Objects.requireNonNull(task);
        if (runtimeInstanceId == null) return false;
        return insert(task, runtimeInstanceId, null);
    }

    /**
     * 在调用方短事务中检查可信执行归属，并把 PENDING 任务及其代次一起持久化。
     * 调用方还须在同一事务内完成必要的授权状态推进和严格审计；本方法不创建独立提交边界。
     * @param task 服务端形成的运行任务，来源必须精确匹配不可变绑定
     * @param instanceId 当前执行实例标识
     * @param epoch 当前执行片段持有的代次
     * @return 归属及来源有效且任务成功插入时为 true，否则为 false
     */
    public boolean insert(ClientProjectTask task, UUID instanceId, long epoch) {
        return insert(task, instanceId, Long.valueOf(epoch));
    }

    private boolean insert(ClientProjectTask task, UUID instanceId, Long expectedEpoch) {
        Objects.requireNonNull(task); Objects.requireNonNull(instanceId);
        if (task.runId() == null || task.state() != ClientProjectTask.State.PENDING
                || expectedEpoch != null && expectedEpoch < 1) return false;
        ownership.requireTransaction();
        return Boolean.TRUE.equals(ownership.transaction().execute(status -> {
            // 设备锁必须先于归属锁：任务外键也会访问设备行，反向获取会与设备确认造成死锁。
            if (jdbc.sql("SELECT id FROM local_client_devices WHERE tenant_id=:tenant"
                    + " AND id=:device AND state='ACTIVE' AND " + deviceOwner + " FOR UPDATE")
                    .param("tenant", task.tenantId().toString()).param("device", task.deviceId().toString())
                    .param("owner", task.ownerId()).query(String.class).optional().isEmpty()) return false;
            // 归属锁先于任务写，接管与受保护领取不得以相反顺序取得任务行锁。
            var current = ownership.lockCurrentForInstance(task.tenantId(), task.ownerId(),
                    task.runId(), instanceId).orElse(null);
            if (current == null || !permits(task, current)
                    || expectedEpoch != null && expectedEpoch.longValue() != current.epoch()) return false;
            long matching = jdbc.sql("SELECT COUNT(*) FROM client_project_runs WHERE tenant_id=:tenant"
                    + " AND run_id=:run AND project_id=:project AND agent_id=:agent"
                    + " AND conversation_id=:conversation AND device_id=:device AND " + sourceOwner)
                    .param("tenant", task.tenantId().toString()).param("run", task.runId().toString())
                    .param("project", task.projectId().toString()).param("agent", task.agentId().toString())
                    .param("conversation", task.conversationId().toString()).param("device", task.deviceId().toString())
                    .param("owner", task.ownerId()).query(Long.class).single();
            if (matching != 1 || !insertRow(task)) return false;
            Timestamp now = jdbc.sql(clockQuery).query(Timestamp.class).single();
            int inserted = jdbc.sql("INSERT INTO client_project_task_fences"
                    + " (tenant_id,task_id,run_id,owner_id,instance_id,epoch,created_at)"
                    + " VALUES (:tenant,:task,:run,:owner,:instance,:epoch,:now)")
                    .param("tenant", task.tenantId().toString()).param("task", task.id().toString())
                    .param("run", task.runId().toString()).param("owner", task.ownerId())
                    .param("instance", instanceId.toString()).param("epoch", current.epoch())
                    .param("now", now).update();
            if (inserted != 1) throw new IllegalStateException("任务代次无法原子保存");
            return true;
        }));
    }

    private static boolean permits(ClientProjectTask task, ClientProjectRunOwnership current) {
        return current.phase() == ClientProjectRunOwnership.Phase.EXECUTING
                || current.phase() == ClientProjectRunOwnership.Phase.RECOVERING
                && (task.kind() == ClientProjectTask.Kind.COMMAND_STATUS
                    || task.kind() == ClientProjectTask.Kind.STOP_COMMAND);
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
                .param("run", task.runId().toString())
                .param("agent", task.agentId() == null ? null : task.agentId().toString())
                .param("conversation", task.conversationId() == null ? null
                        : task.conversationId().toString())
                .param("operation", task.operationId() == null ? null
                        : task.operationId().toString())
                .param("command", task.commandId() == null ? null : task.commandId().toString())
                .param("kind", task.kind().name()).param("request", task.encryptedRequest())
                .param("state", task.state().name()).param("created", Timestamp.from(task.createdAt()))
                .param("expires", Timestamp.from(task.expiresAt())).update() == 1;
    }
}
