package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectRunOwnership;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 持有恢复代次行锁期间隔离原 Run 的未完成设备任务和副作用意图。
 *
 * <p>仅供 {@link JdbcClientProjectRunOwnershipRepository} 的可信接管回调调用；调用者必须在同一
 * 事务写严格审计。该类不停止设备上的进程，也不证明已派发任务没有产生副作用。</p>
 */
public final class JdbcClientProjectRunIsolation {
    private final JdbcClient jdbc;
    private final JdbcClientProjectRunOwnershipRepository ownership;
    private final String owner;
    private final String clockQuery;

    /** @param ownership 提供同一数据源和事务的持久归属仓储 */
    public JdbcClientProjectRunIsolation(JdbcClientProjectRunOwnershipRepository ownership) {
        this.ownership = Objects.requireNonNull(ownership);
        this.jdbc = ownership.jdbc();
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.clockQuery = jdbc.sql("SELECT version()").query(String.class).single().startsWith("PostgreSQL")
                ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
    }

    /**
     * 撤销未使用提案，隔离可能已发生副作用的命令和文件操作，并终结旧代次未完成任务。
     *
     * <p>只有可信的当前 RECOVERING 代次能执行；任一更新或随后审计失败时，调用者必须令同一
     * 事务回滚。已派发任务归 UNKNOWN，不自动重放；命令进程和本地根仍需设备回执与人工对账。</p>
     * @param previous 已锁定的过期归属
     * @param next 本事务取得的下一恢复代次
     * @return 各类被隔离或撤销的记录数量
     */
    public Counts quarantine(ClientProjectRunOwnership previous, ClientProjectRunOwnership next) {
        Objects.requireNonNull(previous); Objects.requireNonNull(next);
        ownership.requireTransaction();
        if (!previous.tenantId().equals(next.tenantId()) || !previous.runId().equals(next.runId())
                || !previous.ownerId().equals(next.ownerId()) || next.epoch() != Math.addExact(previous.epoch(), 1)
                || next.phase() != ClientProjectRunOwnership.Phase.RECOVERING
                || ownership.lockRecovery(next.tenantId(), next.ownerId(), next.runId(),
                        next.instanceId(), next.epoch()).isEmpty()) {
            throw new IllegalStateException("设备项目恢复归属不可用");
        }
        Instant now = jdbc.sql(clockQuery).query(Timestamp.class).single().toInstant();
        // 不猜测排队命令是否已被设备领取：任何已批准但未收敛的命令均保留 UNKNOWN。
        int commands = jdbc.sql("UPDATE client_project_commands SET state=CASE WHEN state='PROPOSED'"
                + " THEN 'CANCELLED' ELSE 'UNKNOWN' END,version=version+1,"
                + "updated_at=CASE WHEN updated_at>:now THEN updated_at ELSE :now END"
                + " WHERE tenant_id=:tenant AND run_id=:run AND state IN"
                + " ('PROPOSED','QUEUED','RUNNING','STOP_REQUESTED') AND " + owner)
                .param("now", Timestamp.from(now)).param("tenant", next.tenantId().toString())
                .param("run", next.runId().toString()).param("owner", next.ownerId()).update();
        int operations = jdbc.sql("UPDATE client_project_operations SET state=CASE WHEN state='PROPOSED'"
                + " THEN 'CANCELLED' ELSE 'UNKNOWN' END,version=version+1,"
                + "updated_at=CASE WHEN updated_at>:now THEN updated_at ELSE :now END"
                + " WHERE tenant_id=:tenant AND run_id=:run AND state IN ('PROPOSED','APPLYING') AND " + owner)
                .param("now", Timestamp.from(now)).param("tenant", next.tenantId().toString())
                .param("run", next.runId().toString()).param("owner", next.ownerId()).update();
        int tasks = jdbc.sql("UPDATE client_project_tasks SET state=CASE WHEN state='PENDING'"
                + " THEN 'CANCELLED' ELSE 'UNKNOWN' END,completed_at=:now,version=version+1,"
                + "safe_error='运行归属已变更，原任务不得重放'"
                + " WHERE tenant_id=:tenant AND run_id=:run AND state IN ('PENDING','DISPATCHED') AND " + owner)
                .param("now", Timestamp.from(now)).param("tenant", next.tenantId().toString())
                .param("run", next.runId().toString()).param("owner", next.ownerId()).update();
        return new Counts(commands, operations, tasks);
    }

    /**
     * 一次隔离的记录数量，不包含任务正文或设备路径。
     * @param commands 已撤销或转未知的命令数
     * @param operations 已撤销或转未知的文件意图数
     * @param tasks 已取消或转未知的任务数
     */
    public record Counts(int commands, int operations, int tasks) { }
}
