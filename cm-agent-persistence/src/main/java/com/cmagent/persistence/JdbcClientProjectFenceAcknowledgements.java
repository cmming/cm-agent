package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 在数据库短事务内登记已验签设备的运行代次确认及严格审计。
 *
 * <p>调用方必须先验证设备签名、一次性挑战和包含本机持久化确认的完整请求体；
 * 客户端提交的租户、主体、实例和阶段不能作为可信来源。本组件尚未连接 HTTP 协议，
 * 单独的数据库确认不能证明本机进程已停止或旧副作用没有发生。</p>
 */
public final class JdbcClientProjectFenceAcknowledgements {
    private final JdbcClientProjectRunOwnershipRepository ownership;
    private final JdbcClient jdbc;
    private final JdbcAuditEventRepository audit;
    private final String deviceOwner;
    private final String bindingOwner;
    private final String ackOwner;
    private final String clockQuery;

    /** @param ownership 提供相同数据源、数据库时钟与事务的运行归属仓储 */
    public JdbcClientProjectFenceAcknowledgements(JdbcClientProjectRunOwnershipRepository ownership) {
        this.ownership = Objects.requireNonNull(ownership);
        jdbc = ownership.jdbc();
        audit = new JdbcAuditEventRepository(jdbc, ownership.transaction());
        deviceOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        bindingOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        ackOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        clockQuery = jdbc.sql("SELECT version()").query(String.class).single().startsWith("PostgreSQL")
                ? "SELECT clock_timestamp()" : "SELECT NOW(6)";
    }

    /**
     * 幂等登记设备已持久确认的当前归属；锁顺序为设备、运行归属、确认记录。
     * @param device 已经过签名验证的设备，仍需重新核对数据库状态
     * @param runId 设备请求的目标 Run，必须与其持久来源匹配
     * @param epoch 设备确认的代次，必须精确匹配数据库当前代次
     * @return 当前有效代次已确认或本次确认成功时为 true；来源、租期或阶段不符时为 false
     */
    public boolean acknowledge(LocalClientDevice device, UUID runId, long epoch) {
        Objects.requireNonNull(device); Objects.requireNonNull(runId);
        if (device.state() != LocalClientDevice.State.ACTIVE || epoch < 1) return false;
        return Boolean.TRUE.equals(ownership.transaction().execute(status -> {
            // 设备状态和主体均以数据库为准；不信任请求中自称的租户、主体或设备存活状态。
            if (!lockDevice(device)) return false;
            var current = ownership.lockForDeviceAcknowledgement(device.tenantId(), device.ownerId(), runId, epoch);
            if (current.isEmpty()) return false;
            if (!matchesBinding(device, runId)) return false;
            String phase = current.orElseThrow().phase().name();
            if (hasAcknowledgement(device, runId, epoch, phase)) return true;
            Timestamp now = jdbc.sql(clockQuery).query(Timestamp.class).single();
            int inserted = jdbc.sql("INSERT INTO client_project_run_fence_acknowledgements"
                    + " (tenant_id,run_id,epoch,owner_id,device_id,phase,acknowledged_at)"
                    + " VALUES (:tenant,:run,:epoch,:owner,:device,:phase,:now)")
                    .param("tenant", device.tenantId().toString()).param("run", runId.toString())
                    .param("epoch", epoch).param("owner", device.ownerId()).param("device", device.id().toString())
                    .param("phase", phase).param("now", now).update();
            if (inserted != 1) throw new IllegalStateException("设备代次确认无法保存");
            // 审计失败必须回滚确认，否则调度器会误认为设备已进入新代次。
            audit.append(new AuditEvent(UUID.randomUUID(), device.tenantId(), device.ownerId(),
                    "CLIENT_PROJECT_FENCE_ACK", "CLIENT_PROJECT_RUN", runId.toString(), "ACKNOWLEDGED",
                    "epoch=" + epoch + " phase=" + phase + " device=" + device.id(), Instant.now()));
            return true;
        }));
    }

    /**
     * 获取已验签设备可访问的当前代次和不透明根身份；过期或来源不匹配时拒绝。
     * @param device 已验签设备，仍按数据库当前状态核对
     * @param runId 设备请求的运行标识
     * @return 当前有效的代次和根身份；无权访问或暂不可恢复时为空
     */
    public Optional<Current> current(LocalClientDevice device, UUID runId) {
        Objects.requireNonNull(device); Objects.requireNonNull(runId);
        if (device.state() != LocalClientDevice.State.ACTIVE) return Optional.empty();
        return Objects.requireNonNull(ownership.transaction().execute(status -> {
            if (!lockDevice(device)) return Optional.<Current>empty();
            var current = ownership.lockCurrentForDevice(device.tenantId(), device.ownerId(), runId);
            if (current.isEmpty()) return Optional.empty();
            var root = jdbc.sql("SELECT client_root_id,root_identity FROM client_project_runs"
                    + " WHERE tenant_id=:tenant AND run_id=:run AND device_id=:device AND " + bindingOwner)
                    .param("tenant", device.tenantId().toString()).param("run", runId.toString())
                    .param("device", device.id().toString()).param("owner", device.ownerId())
                    .query((row, index) -> new Root(UUID.fromString(row.getString("client_root_id")),
                            row.getString("root_identity"))).optional();
            if (root.isEmpty()) return Optional.empty();
            var value = current.orElseThrow();
            audit.append(new AuditEvent(UUID.randomUUID(), device.tenantId(), device.ownerId(),
                    "CLIENT_PROJECT_FENCE_READ", "CLIENT_PROJECT_RUN", runId.toString(), "SUCCEEDED",
                    "epoch=" + value.epoch() + " phase=" + value.phase().name() + " device=" + device.id(), Instant.now()));
            return Optional.of(new Current(runId, root.orElseThrow().rootId(), root.orElseThrow().identity(),
                    value.epoch(), value.phase(), hasAcknowledgement(device, runId, value.epoch(), value.phase().name())));
        }));
    }

    /**
     * 找到当前设备尚未确认的首个有效执行或恢复代次。
     *
     * <p>设备领取任务前先调用本查询并把确认要求返回给客户端；查询不改变任务状态，也不证明
     * 设备已经安全落盘。调用方必须等待签名确认写入数据库后再尝试领取。</p>
     *
     * @param device 已验证签名的设备
     * @return 未确认当前代次及不透明根身份；没有待确认运行时为空
     */
    public Optional<Current> awaitingCurrent(LocalClientDevice device) {
        Objects.requireNonNull(device);
        if (device.state() != LocalClientDevice.State.ACTIVE) return Optional.empty();
        return Objects.requireNonNull(ownership.transaction().execute(status -> {
            if (!lockDevice(device)) return Optional.<Current>empty();
            Timestamp now = jdbc.sql(clockQuery).query(Timestamp.class).single();
            return jdbc.sql("SELECT o.run_id,b.client_root_id,b.root_identity,o.epoch,o.phase"
                            + " FROM client_project_run_ownership o"
                            + " JOIN client_project_runs b ON b.tenant_id=o.tenant_id AND b.run_id=o.run_id"
                            + " JOIN runs r ON r.tenant_id=o.tenant_id AND r.id=o.run_id AND r.agent_id=b.agent_id"
                            + " WHERE o.tenant_id=:tenant AND b.device_id=:device AND o.owner_id=:owner"
                            + " AND b.owner_id=:owner AND o.phase IN ('EXECUTING','RECOVERING')"
                            + " AND o.expires_at>:now AND r.workspace_protocol=5 AND r.run_kind='NORMAL'"
                            + " AND r.status IN ('RUNNING','WAITING_APPROVAL','CANCELLING')"
                            + " AND NOT EXISTS (SELECT 1 FROM client_project_run_fence_acknowledgements a"
                            + " WHERE a.tenant_id=o.tenant_id AND a.run_id=o.run_id AND a.epoch=o.epoch"
                            + " AND a.owner_id=o.owner_id AND a.device_id=:device AND a.phase=o.phase)"
                            + " ORDER BY o.created_at,o.run_id LIMIT 1")
                    .param("tenant", device.tenantId().toString()).param("device", device.id().toString())
                    .param("owner", device.ownerId()).param("now", now)
                    .query((row, index) -> new Current(UUID.fromString(row.getString("run_id")),
                            UUID.fromString(row.getString("client_root_id")), row.getString("root_identity"),
                            row.getLong("epoch"),
                            ClientProjectRunOwnership.Phase.valueOf(row.getString("phase")), false))
                    .optional();
        }));
    }

    /**
     * 设备可核对的运行代次和不透明根身份，不包含服务端实例或本机路径。
     * @param runId 已固定来源的运行
     * @param rootId 已登记设备根
     * @param rootIdentity 设备根对象身份摘要
     * @param epoch 当前代次
     * @param phase 执行或受限恢复阶段
     * @param acknowledged 当前设备是否已确认此代次
     */
    public record Current(UUID runId, UUID rootId, String rootIdentity, long epoch,
            ClientProjectRunOwnership.Phase phase, boolean acknowledged) { }
    /** @param rootId 已登记根标识 @param identity 本机根身份摘要 */
    private record Root(UUID rootId, String identity) { }

    /**
     * 在调用方事务中锁定设备与当前归属，并检查该设备已确认指定代次和阶段。
     *
     * <p>只能由已验证设备签名的服务端调用；结果仅在当前事务、行锁释放之前有效。
     * 领取方应继续在此事务锁定任务并做条件状态更新，不能在事务外复用结果。</p>
     * @param device 已验签设备，数据库状态仍会重新核对
     * @param runId 已固定来源的运行
     * @param instanceId 服务端持有的可信实例标识
     * @param epoch 任务绑定的防护代次
     * @param phase 所需执行或恢复阶段
     * @return 设备、来源、租期、归属和确认全部匹配时为 true
     */
    public boolean lockConfirmed(LocalClientDevice device, UUID runId, UUID instanceId, long epoch,
            ClientProjectRunOwnership.Phase phase) {
        Objects.requireNonNull(device); Objects.requireNonNull(runId); Objects.requireNonNull(instanceId);
        Objects.requireNonNull(phase);
        ownership.requireTransaction();
        if (device.state() != LocalClientDevice.State.ACTIVE || epoch < 1
                || phase == ClientProjectRunOwnership.Phase.CLOSED || !lockDevice(device)) return false;
        var current = ownership.lockForDeviceAcknowledgement(device.tenantId(), device.ownerId(), runId, epoch);
        return current.filter(value -> value.instanceId().equals(instanceId) && value.phase() == phase).isPresent()
                && matchesBinding(device, runId) && hasAcknowledgement(device, runId, epoch, phase.name());
    }

    private boolean lockDevice(LocalClientDevice device) {
        return jdbc.sql("SELECT id FROM local_client_devices WHERE tenant_id=:tenant"
                + " AND id=:device AND state='ACTIVE' AND " + deviceOwner + " FOR UPDATE")
                .param("tenant", device.tenantId().toString()).param("device", device.id().toString())
                .param("owner", device.ownerId()).query(String.class).optional().isPresent();
    }

    private boolean matchesBinding(LocalClientDevice device, UUID runId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_runs WHERE tenant_id=:tenant"
                + " AND run_id=:run AND device_id=:device AND " + bindingOwner)
                .param("tenant", device.tenantId().toString()).param("run", runId.toString())
                .param("device", device.id().toString()).param("owner", device.ownerId())
                .query(Long.class).single() == 1;
    }

    private boolean hasAcknowledgement(LocalClientDevice device, UUID runId, long epoch, String phase) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_run_fence_acknowledgements"
                + " WHERE tenant_id=:tenant AND run_id=:run AND epoch=:epoch AND device_id=:device"
                + " AND phase=:phase AND " + ackOwner)
                .param("tenant", device.tenantId().toString()).param("run", runId.toString())
                .param("epoch", epoch).param("device", device.id().toString())
                .param("phase", phase).param("owner", device.ownerId()).query(Long.class).single() == 1;
    }
}
