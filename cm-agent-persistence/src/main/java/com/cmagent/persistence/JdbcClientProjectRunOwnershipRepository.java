package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.repository.ClientProjectRunOwnershipRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

/**
 * 设备项目运行归属的 JDBC 行锁和防护代次实现。
 *
 * <p>首发、锁定与续期必须在调用方短事务中执行，锁一直保留到事务结束。接管使用事务模板
 * 调用可信的隔离与严格审计回调；本仓储没有装配到完整运行路径之前不能提供自动接管。</p>
 */
public final class JdbcClientProjectRunOwnershipRepository implements ClientProjectRunOwnershipRepository {
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final TransactionTemplate mandatoryTransaction;
    private final RecoveryBarrier recoveryBarrier;
    private final String ownershipOwner;
    private final String bindingOwner;
    private final String runOwner;
    private final String databaseClockQuery;

    /** @param dataSource 归属、隔离与审计使用的同一数据源 @param transaction 绑定该数据源的事务模板 */
    public JdbcClientProjectRunOwnershipRepository(DataSource dataSource, TransactionTemplate transaction) {
        this(dataSource, transaction, null);
    }

    /** 仅同包测试可注入失败屏障；公开装配始终使用实际 JDBC 隔离与审计。 */
    JdbcClientProjectRunOwnershipRepository(DataSource dataSource, TransactionTemplate transaction,
            RecoveryBarrier testBarrier) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.transaction = Objects.requireNonNull(transaction);
        // 单纯存在事务不保证 JDBC 操作参与同一数据库事务；错误装配必须在启用前失败。
        if (!(transaction.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != dataSource) {
            throw new IllegalArgumentException("运行归属与事务管理器必须使用同一数据源");
        }
        this.mandatoryTransaction = new TransactionTemplate(manager);
        this.mandatoryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.jdbc = JdbcClient.create(dataSource);
        ownershipOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        bindingOwner = JdbcOwnerPredicate.equal(jdbc, "p.owner_id", ":owner");
        runOwner = JdbcOwnerPredicate.equal(jdbc, "r.principal_id", ":owner");
        // PostgreSQL 的 CURRENT_TIMESTAMP 固定为事务开始时间；等锁之后必须读取实际数据库时钟。
        databaseClockQuery = jdbc.sql("SELECT version()").query(String.class).single().startsWith("PostgreSQL")
                ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
        // 接管屏障由仓储固定，调用方不能传入空回调绕过隔离和审计。
        recoveryBarrier = testBarrier == null ? new JdbcClientProjectRecoveryBarrier(this) : testBarrier;
    }

    @Override public Optional<ClientProjectRunOwnership> insertInitial(ClientProjectBinding binding,
            UUID instanceId, Duration duration) {
        requireTransaction();
        Objects.requireNonNull(binding);
        Objects.requireNonNull(instanceId);
        validateDuration(duration);
        if (binding.runId() == null) return Optional.empty();
        if (!ClientProjectRunCreationWitness.consume(binding)) return Optional.empty();
        // 先锁不可变来源行：不同实例不能分别检查不存在后同时插入首代。
        var source = jdbc.sql("SELECT p.run_id FROM client_project_runs p JOIN runs r"
                + " ON r.tenant_id=p.tenant_id AND r.id=p.run_id WHERE p.tenant_id=:tenant"
                + " AND p.run_id=:run AND p.project_id=:project AND p.agent_id=:agent"
                + " AND p.conversation_id=:conversation AND p.device_id=:device AND p.client_root_id=:root"
                + " AND p.root_identity=:identity AND p.project_version=:version"
                + " AND r.agent_id=:agent AND r.workspace_protocol=5 AND r.run_kind='NORMAL' AND r.status='RUNNING'"
                + " AND " + bindingOwner + " AND " + runOwner + " FOR UPDATE")
                .param("tenant", binding.tenantId().toString()).param("run", binding.runId().toString())
                .param("owner", binding.ownerId()).param("project", binding.projectId().toString())
                .param("agent", binding.agentId().toString()).param("conversation", binding.conversationId().toString())
                .param("device", binding.deviceId().toString()).param("root", binding.clientRootId().toString())
                .param("identity", binding.rootIdentity()).param("version", binding.projectVersion())
                .query(String.class).optional();
        if (source.isEmpty() || findLocked(binding.tenantId(), binding.ownerId(), binding.runId()).isPresent()) {
            return Optional.empty();
        }
        Instant now = databaseNow();
        var ownership = ClientProjectRunOwnership.initial(binding, instanceId, now, normalized(duration));
        int inserted = jdbc.sql("INSERT INTO client_project_run_ownership"
                + " (tenant_id,run_id,owner_id,instance_id,epoch,phase,expires_at,created_at,updated_at)"
                + " VALUES (:tenant,:run,:owner,:instance,1,'EXECUTING',:expires,:now,:now)")
                .param("tenant", ownership.tenantId().toString()).param("run", ownership.runId().toString())
                .param("owner", ownership.ownerId()).param("instance", ownership.instanceId().toString())
                .param("expires", Timestamp.from(ownership.expiresAt())).param("now", Timestamp.from(now)).update();
        if (inserted != 1) throw new IllegalStateException("运行归属无法保存");
        return Optional.of(ownership);
    }

    @Override public Optional<ClientProjectRunOwnership> lockExecution(UUID tenantId, String ownerId,
            UUID runId, UUID instanceId, long epoch) {
        requireTransaction();
        var current = findLocked(tenantId, ownerId, runId);
        Instant now = databaseNow();
        return current.filter(value -> value.permitsExecution(instanceId, epoch, now));
    }

    @Override public Optional<ClientProjectRunOwnership> lockRecovery(UUID tenantId, String ownerId,
            UUID runId, UUID instanceId, long epoch) {
        requireTransaction();
        var current = findLocked(tenantId, ownerId, runId);
        Instant now = databaseNow();
        return current.filter(value -> value.permitsRecovery(instanceId, epoch, now));
    }

    /**
     * 锁定当前进程仍持有的执行或恢复归属，不采用调用方提供的代次。
     *
     * <p>任务写入和 Run 状态收口调用本方法后仍须在同一事务完成自身条件写；查询成功只表示
     * 锁内快照未过期且实例匹配，事务释放后不得继续使用。</p>
     *
     * @param tenantId 可信租户
     * @param ownerId 原 Run 主体
     * @param runId 已固定来源的 Run
     * @param instanceId 当前 Server 随机实例
     * @return 未过期的执行或恢复归属；被接管、关闭或过期时为空
     */
    public Optional<ClientProjectRunOwnership> lockCurrentForInstance(UUID tenantId, String ownerId,
            UUID runId, UUID instanceId) {
        requireTransaction();
        var current = findLocked(tenantId, ownerId, runId);
        Instant now = databaseNow();
        return current.filter(value -> value.phase() != ClientProjectRunOwnership.Phase.CLOSED
                && value.instanceId().equals(instanceId) && value.expiresAt().isAfter(now));
    }

    /**
     * 仅当前实例、代次和未过期执行/恢复阶段匹配时关闭归属。
     *
     * @param tenantId 可信租户
     * @param ownerId 原 Run 主体
     * @param runId Run 标识
     * @param instanceId 当前 Server 随机实例
     * @return 本次条件更新成功时 true；已被接管或过期时 false
     */
    public boolean closeCurrent(UUID tenantId, String ownerId, UUID runId, UUID instanceId) {
        requireTransaction();
        var current = lockCurrentForInstance(tenantId, ownerId, runId, instanceId);
        if (current.isEmpty()) return false;
        var value = current.orElseThrow();
        Instant now = databaseNow();
        return jdbc.sql("UPDATE client_project_run_ownership SET phase='CLOSED',updated_at=:now"
                        + " WHERE tenant_id=:tenant AND run_id=:run AND owner_id=:owner"
                        + " AND instance_id=:instance AND epoch=:epoch AND phase=:phase AND expires_at>:now")
                .param("now", Timestamp.from(now)).param("tenant", tenantId.toString())
                .param("run", runId.toString()).param("owner", ownerId)
                .param("instance", instanceId.toString()).param("epoch", value.epoch())
                .param("phase", value.phase().name()).update() == 1;
    }

    /** 设备确认只能锁定未过期的现有归属；实例和阶段由数据库而不是设备请求决定。 */
    Optional<ClientProjectRunOwnership> lockForDeviceAcknowledgement(UUID tenantId, String ownerId,
            UUID runId, long epoch) {
        return lockCurrentForDevice(tenantId, ownerId, runId).filter(value -> value.epoch() == epoch);
    }

    /** 读取设备可确认的当前阶段，拒绝过期或关闭状态；锁保持到当前事务结束。 */
    Optional<ClientProjectRunOwnership> lockCurrentForDevice(UUID tenantId, String ownerId, UUID runId) {
        requireTransaction();
        var current = findLocked(tenantId, ownerId, runId);
        Instant now = databaseNow();
        return current.filter(value -> value.permitsExecution(value.instanceId(), value.epoch(), now)
                || value.permitsRecovery(value.instanceId(), value.epoch(), now));
    }

    @Override public Optional<ClientProjectRunOwnership> renew(UUID tenantId, String ownerId,
            UUID runId, UUID instanceId, long epoch, Duration duration) {
        requireTransaction();
        validateDuration(duration);
        var current = findLocked(tenantId, ownerId, runId);
        if (current.isEmpty()) return Optional.empty();
        Instant now = databaseNow();
        var previous = current.orElseThrow();
        if (!(previous.permitsExecution(instanceId, epoch, now)
                || previous.permitsRecovery(instanceId, epoch, now))) return Optional.empty();
        // 提前续期不得缩短现有期限；条件仍须在持有行锁的事务内验证。
        if (!now.plus(normalized(duration)).isAfter(previous.expiresAt())) return current;
        var next = previous.renew(instanceId, epoch, now, normalized(duration));
        return update(previous, next, now) ? Optional.of(next) : Optional.empty();
    }

    @Override public Optional<ClientProjectRunOwnership> takeOverForRecovery(UUID tenantId,
            String ownerId, UUID runId, long expectedEpoch, UUID nextInstance, Duration duration) {
        validateDuration(duration);
        return Objects.requireNonNull(transaction.execute(status -> takeOverLocked(tenantId,
                ownerId, runId, expectedEpoch, nextInstance, duration)));
    }

    private Optional<ClientProjectRunOwnership> takeOverLocked(UUID tenantId,
            String ownerId, UUID runId, long expectedEpoch, UUID nextInstance, Duration duration) {
        requireTransaction();
        var current = findLocked(tenantId, ownerId, runId);
        if (current.isEmpty()) return Optional.empty();
        var previous = current.orElseThrow();
        Instant now = databaseNow();
        if (previous.epoch() != expectedEpoch || previous.phase() == ClientProjectRunOwnership.Phase.CLOSED
                || previous.expiresAt().isAfter(now) || previous.instanceId().equals(nextInstance)) {
            return Optional.empty();
        }
        var next = previous.takeOver(nextInstance, now, normalized(duration));
        if (!update(previous, next, now)) return Optional.empty();
        // 回调失败由事务模板回滚归属、隔离和审计，不能先提交可用恢复代次。
        recoveryBarrier.quarantineAndAudit(previous, next);
        return Optional.of(next);
    }

    private Optional<ClientProjectRunOwnership> findLocked(UUID tenantId, String ownerId, UUID runId) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(ownerId); Objects.requireNonNull(runId);
        return jdbc.sql("SELECT tenant_id,run_id,owner_id,instance_id,epoch,phase,expires_at"
                + " FROM client_project_run_ownership WHERE tenant_id=:tenant AND run_id=:run AND "
                + ownershipOwner + " FOR UPDATE")
                .param("tenant", tenantId.toString()).param("run", runId.toString())
                .param("owner", ownerId).query(this::map).optional();
    }

    private boolean update(ClientProjectRunOwnership previous, ClientProjectRunOwnership next, Instant now) {
        return jdbc.sql("UPDATE client_project_run_ownership SET instance_id=:nextInstance,epoch=:nextEpoch,"
                + "phase=:nextPhase,expires_at=:expires,updated_at=:now WHERE tenant_id=:tenant"
                + " AND run_id=:run AND instance_id=:instance AND epoch=:epoch AND phase=:phase AND " + ownershipOwner)
                .param("nextInstance", next.instanceId().toString()).param("nextEpoch", next.epoch())
                .param("nextPhase", next.phase().name()).param("expires", Timestamp.from(next.expiresAt()))
                .param("now", Timestamp.from(now)).param("tenant", previous.tenantId().toString())
                .param("run", previous.runId().toString()).param("owner", previous.ownerId())
                .param("instance", previous.instanceId().toString()).param("epoch", previous.epoch())
                .param("phase", previous.phase().name()).update() == 1;
    }

    private ClientProjectRunOwnership map(ResultSet row, int index) throws SQLException {
        return new ClientProjectRunOwnership(UUID.fromString(row.getString("tenant_id")),
                UUID.fromString(row.getString("run_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("instance_id")), row.getLong("epoch"),
                ClientProjectRunOwnership.Phase.valueOf(row.getString("phase")),
                row.getTimestamp("expires_at").toInstant());
    }

    private Instant databaseNow() {
        return jdbc.sql(databaseClockQuery).query(Timestamp.class).single().toInstant();
    }

    private static Duration normalized(Duration duration) { return duration.truncatedTo(ChronoUnit.MICROS); }

    private static void validateDuration(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("运行归属租期必须在一秒至五分钟内");
        }
    }

    void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("运行归属操作必须在短事务中执行");
        }
        // 另一个数据源的事务可能为本数据源绑定非事务连接；让本管理器确认自身已有活动事务。
        mandatoryTransaction.executeWithoutResult(status -> { });
    }

    /** 隔离操作必须复用此访问入口，避免不同数据源导致回滚不完整。 */
    JdbcClient jdbc() { return jdbc; }

    /** 严格审计与归属操作复用同一数据源事务模板。 */
    TransactionTemplate transaction() { return transaction; }
}
