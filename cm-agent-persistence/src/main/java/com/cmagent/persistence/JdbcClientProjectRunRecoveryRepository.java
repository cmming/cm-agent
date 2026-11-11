package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectRunOwnership;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 面向系统恢复器的设备项目归属发现、心跳和确认扫描。
 *
 * <p>查询结果只用于内部调度，不直接授权设备或用户操作。每次恢复推进仍由
 * {@link JdbcClientProjectRunOwnershipRepository} 的行锁和条件更新决定，发现结果过期或竞争失败
 * 时调用方必须跳过，不能把一次查询视为永久执行许可。</p>
 */
public final class JdbcClientProjectRunRecoveryRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String databaseClockQuery;

    /**
     * @param jdbc 与归属表一致的数据访问入口
     * @param transaction 与相同数据源绑定的短事务模板
     */
    public JdbcClientProjectRunRecoveryRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.databaseClockQuery = jdbc.sql("SELECT version()").query(String.class).single()
                .startsWith("PostgreSQL") ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
    }

    /**
     * 读取已过期且 Run 仍未收口的归属候选。
     *
     * @param limit 单批上限
     * @return 仅含恢复所需标识的候选；结果不是接管成功证明
     */
    public List<Candidate> findExpired(int limit) {
        requireLimit(limit);
        Instant now = databaseNow();
        return jdbc.sql("SELECT o.tenant_id,o.owner_id,o.run_id,o.instance_id,o.epoch,o.phase"
                + " FROM client_project_run_ownership o"
                + " JOIN client_project_runs b ON b.tenant_id=o.tenant_id AND b.run_id=o.run_id"
                + " JOIN runs r ON r.tenant_id=o.tenant_id AND r.id=o.run_id AND r.agent_id=b.agent_id"
                + " WHERE o.phase IN ('EXECUTING','RECOVERING') AND o.expires_at<=:now"
                + " AND r.workspace_protocol=5 AND r.run_kind='NORMAL'"
                    + " AND r.status IN ('RUNNING','WAITING_APPROVAL','CANCELLING')"
                + " ORDER BY o.expires_at,o.tenant_id,o.run_id LIMIT :limit")
                .param("now", Timestamp.from(now)).param("limit", limit)
                .query(this::map).list();
    }

    /**
     * 读取当前本实例已进入恢复阶段且设备已确认该代次的 Run。
     *
     * @param instanceId 当前 Server 随机实例
     * @param limit 单批上限
     * @return 可安全收口 Run 元数据的候选；不表示外部进程已经停止
     */
    public List<Candidate> findAcknowledgedRecovering(UUID instanceId, int limit) {
        Objects.requireNonNull(instanceId);
        requireLimit(limit);
        return jdbc.sql("SELECT o.tenant_id,o.owner_id,o.run_id,o.instance_id,o.epoch,o.phase"
                + " FROM client_project_run_ownership o"
                + " JOIN client_project_runs b ON b.tenant_id=o.tenant_id AND b.run_id=o.run_id"
                + " JOIN client_project_run_fence_acknowledgements a"
                + " ON a.tenant_id=o.tenant_id AND a.run_id=o.run_id AND a.epoch=o.epoch"
                + " AND a.owner_id=o.owner_id AND a.device_id=b.device_id AND a.phase='RECOVERING'"
                + " JOIN runs r ON r.tenant_id=o.tenant_id AND r.id=o.run_id AND r.agent_id=b.agent_id"
                + " WHERE o.instance_id=:instance AND o.phase='RECOVERING'"
                + " AND r.workspace_protocol=5 AND r.run_kind='NORMAL'"
                + " AND r.status IN ('RUNNING','WAITING_APPROVAL','CANCELLING')"
                + " ORDER BY o.updated_at,o.tenant_id,o.run_id LIMIT :limit")
                .param("instance", instanceId.toString()).param("limit", limit)
                .query(this::map).list();
    }

    /**
     * 延长当前实例仍有效的执行或恢复租期。
     *
     * @param instanceId 当前 Server 随机实例
     * @param duration 一秒至五分钟的新租期
     * @return 实际续期的行数；到期、被接管或 Run 已终态的行保持原样
     */
    public int renewInstance(UUID instanceId, Duration duration) {
        Objects.requireNonNull(instanceId);
        validateDuration(duration);
        return Objects.requireNonNull(transaction.execute(status -> {
            Instant now = databaseNow();
            var rows = jdbc.sql("SELECT o.tenant_id,o.owner_id,o.run_id,o.instance_id,o.epoch,o.phase"
                    + " FROM client_project_run_ownership o"
                    + " JOIN client_project_runs b ON b.tenant_id=o.tenant_id AND b.run_id=o.run_id"
                    + " JOIN runs r ON r.tenant_id=o.tenant_id AND r.id=o.run_id AND r.agent_id=b.agent_id"
                    + " WHERE o.instance_id=:instance AND o.phase IN ('EXECUTING','RECOVERING')"
                    + " AND o.expires_at>:now AND r.workspace_protocol=5 AND r.run_kind='NORMAL'"
                    + " AND r.status IN ('RUNNING','WAITING_APPROVAL','CANCELLING')"
                    + " FOR UPDATE")
                    .param("instance", instanceId.toString()).param("now", Timestamp.from(now))
                    .query(this::map).list();
            Timestamp expires = Timestamp.from(now.plus(duration).truncatedTo(ChronoUnit.MICROS));
            int renewed = 0;
            for (Candidate row : rows) {
                renewed += jdbc.sql("UPDATE client_project_run_ownership SET expires_at=:expires,updated_at=:now"
                                + " WHERE tenant_id=:tenant AND run_id=:run AND owner_id=:owner"
                                + " AND instance_id=:instance AND epoch=:epoch AND phase=:phase AND expires_at>:now")
                        .param("expires", expires).param("now", Timestamp.from(now))
                        .param("tenant", row.tenantId().toString()).param("run", row.runId().toString())
                        .param("owner", row.ownerId()).param("instance", row.instanceId().toString())
                        .param("epoch", row.epoch()).param("phase", row.phase().name()).update();
            }
            return renewed;
        }));
    }

    private Candidate map(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Candidate(UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("run_id")), UUID.fromString(row.getString("instance_id")),
                row.getLong("epoch"), ClientProjectRunOwnership.Phase.valueOf(row.getString("phase")));
    }

    private Instant databaseNow() {
        return jdbc.sql(databaseClockQuery).query(Timestamp.class).single().toInstant();
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("恢复扫描批次必须在一至一千之间");
    }

    private static void validateDuration(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("运行归属租期必须在一秒至五分钟内");
        }
    }

    /**
     * 系统恢复扫描使用的内部归属摘要。
     *
     * @param tenantId 可信租户
     * @param ownerId 原 Run 主体
     * @param runId Run 标识
     * @param instanceId 数据库当前实例
     * @param epoch 数据库当前代次
     * @param phase 数据库当前阶段
     */
    public record Candidate(UUID tenantId, String ownerId, UUID runId, UUID instanceId,
            long epoch, ClientProjectRunOwnership.Phase phase) {
        public Candidate {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(instanceId);
            Objects.requireNonNull(phase);
        }
    }
}
