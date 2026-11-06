package com.cmagent.persistence;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.domain.LocalClientPairing;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.repository.LocalClientOperationRepository;
import com.cmagent.core.repository.LocalClientPairingRepository;
import com.cmagent.core.repository.LocalClientRunGrantRepository;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于 JDBC 的设备绑定与短期单次挑战仓储。
 *
 * <p>每次查询都限定可信租户和逐字匹配的原主体。写入按租户行、设备行顺序取得锁；调用方
 * 共享外层事务时也须遵守该顺序，并在事务中完成签名验证后的派发登记及严格审计。</p>
 */
public final class JdbcLocalClientDeviceRepository implements LocalClientDeviceRepository,
        LocalClientRunGrantRepository, LocalClientPairingRepository, LocalClientOperationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    /** 固定列名由本仓储提供，避免 MySQL 默认大小写及尾空格规则合并不同主体。 */
    private final String deviceOwner;
    private final String challengeOwner;
    private final String joinedDeviceOwner;
    private final String joinedChallengeOwner;
    private final String grantOwner;
    private final String joinedGrantOwner;
    private final String joinedRunOwner;
    private final String joinedConversationOwner;
    private final String pairingOwner;
    private final String operationOwner;
    private final String joinedOperationOwner;

    /**
     * @param jdbc 与事务模板使用同一数据源的命名参数客户端
     * @param transaction 由宿主持有的短事务模板，允许加入调用方事务
     */
    public JdbcLocalClientDeviceRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 客户端不能为空");
        this.transaction = Objects.requireNonNull(transaction, "事务模板不能为空");
        this.deviceOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.challengeOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.joinedDeviceOwner = JdbcOwnerPredicate.equal(jdbc, "d.owner_id", ":owner");
        this.joinedChallengeOwner = JdbcOwnerPredicate.equal(jdbc, "c.owner_id", ":owner");
        this.grantOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.joinedGrantOwner = JdbcOwnerPredicate.equal(jdbc, "g.owner_id", ":owner");
        this.joinedRunOwner = JdbcOwnerPredicate.equal(jdbc, "r.principal_id", ":owner");
        this.joinedConversationOwner = JdbcOwnerPredicate.equal(jdbc, "c.created_by", ":owner");
        this.pairingOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.operationOwner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.joinedOperationOwner = JdbcOwnerPredicate.equal(jdbc, "o.owner_id", ":owner");
    }

    @Override
    public boolean insert(LocalClientDevice device) {
        Objects.requireNonNull(device, "设备不能为空");
        if (device.state() != LocalClientDevice.State.ACTIVE || device.version() != 1) {
            throw new IllegalArgumentException("设备只能以初始活动版本登记");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            // 使用租户行串行化同标识登记，避免 PostgreSQL 唯一键异常污染外层审计事务。
            if (!lockTenant(device.tenantId()) || exists(device.tenantId(), device.id())) return false;
            return jdbc.sql("""
                    INSERT INTO local_client_devices
                    (tenant_id,id,owner_id,display_name,platform,public_key,state,version,created_at,updated_at)
                    VALUES (:tenant,:id,:owner,:name,:platform,:publicKey,'ACTIVE',1,:created,:updated)
                    """)
                    .param("tenant", device.tenantId().toString()).param("id", device.id().toString())
                    .param("owner", device.ownerId()).param("name", device.displayName())
                    .param("platform", device.platform().name()).param("publicKey", device.publicKey())
                    .param("created", Timestamp.from(device.createdAt())).param("updated", Timestamp.from(device.updatedAt()))
                    .update() == 1;
        }));
    }

    @Override
    public Optional<LocalClientDevice> find(UUID tenantId, String ownerId, UUID deviceId) {
        return device(tenantId, ownerId, deviceId, false);
    }

    @Override
    public List<LocalClientDevice> listByOwner(UUID tenantId, String ownerId) {
        return jdbc.sql("SELECT * FROM local_client_devices WHERE tenant_id=:tenant AND " + deviceOwner
                        + " ORDER BY created_at DESC,id")
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .query(this::device).list();
    }

    @Override
    public Optional<LocalClientDevice> findAnyById(UUID deviceId) {
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        return jdbc.sql("SELECT * FROM local_client_devices WHERE id=:device")
                .param("device", deviceId.toString()).query(this::device).optional();
    }

    @Override
    public boolean revoke(UUID tenantId, String ownerId, UUID deviceId, long expectedVersion, Instant now) {
        Objects.requireNonNull(now, "撤销时间不能为空");
        if (expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("设备版本无效");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            var current = device(tenantId, ownerId, deviceId, true).orElse(null);
            if (current == null || current.state() != LocalClientDevice.State.ACTIVE
                    || current.version() != expectedVersion || now.isBefore(current.updatedAt())) return false;
            return jdbc.sql("UPDATE local_client_devices SET state='REVOKED',version=version+1,updated_at=:now "
                            + "WHERE tenant_id=:tenant AND id=:device AND " + deviceOwner
                            + " AND state='ACTIVE' AND version=:version")
                    .param("tenant", tenantId.toString()).param("owner", ownerId).param("device", deviceId.toString())
                    .param("version", expectedVersion).param("now", Timestamp.from(now)).update() == 1;
        }));
    }

    @Override
    public boolean insertChallenge(LocalClientChallenge challenge) {
        Objects.requireNonNull(challenge, "挑战不能为空");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            // 跨设备的相同挑战标识也必须串行查重，锁顺序固定为租户行再设备行。
            if (!lockTenant(challenge.tenantId())) return false;
            // 与撤销/消费锁定同一设备行，确保撤销先完成时不会再签发有效挑战。
            var device = device(challenge.tenantId(), challenge.ownerId(), challenge.deviceId(), true).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE
                    || challenge.createdAt().isBefore(device.createdAt())
                    || challengeExists(challenge.tenantId(), challenge.id())) return false;
            return jdbc.sql("""
                    INSERT INTO local_client_challenges
                    (tenant_id,id,owner_id,device_id,nonce,created_at,expires_at,consumed_at)
                    VALUES (:tenant,:id,:owner,:device,:nonce,:created,:expires,NULL)
                    """)
                    .param("tenant", challenge.tenantId().toString()).param("id", challenge.id().toString())
                    .param("owner", challenge.ownerId()).param("device", challenge.deviceId().toString())
                    .param("nonce", Base64.getEncoder().encodeToString(challenge.nonce()))
                    .param("created", Timestamp.from(challenge.createdAt()))
                    .param("expires", Timestamp.from(challenge.expiresAt())).update() == 1;
        }));
    }

    @Override
    public Optional<LocalClientChallenge> findChallenge(UUID tenantId, String ownerId, UUID deviceId,
                                                         UUID challengeId, Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return jdbc.sql("""
                SELECT c.tenant_id,c.id,c.owner_id,c.device_id,c.nonce,c.created_at,c.expires_at
                FROM local_client_challenges c JOIN local_client_devices d
                  ON d.tenant_id=c.tenant_id AND d.id=c.device_id
                WHERE c.tenant_id=:tenant AND c.device_id=:device AND c.id=:challenge
                  AND c.consumed_at IS NULL AND c.created_at<=:now AND c.expires_at>:now
                  AND d.state='ACTIVE' AND
                """ + joinedDeviceOwner + " AND " + joinedChallengeOwner)
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("device", deviceId.toString())
                .param("challenge", challengeId.toString()).param("now", Timestamp.from(now))
                .query(this::challenge).optional();
    }

    @Override
    public boolean consumeChallenge(UUID tenantId, String ownerId, UUID deviceId, UUID challengeId, Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            // 同一设备行锁定义撤销与消费先后；挑战行条件更新只允许一个调用取得单次使用权。
            var device = device(tenantId, ownerId, deviceId, true).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE) return false;
            return jdbc.sql("UPDATE local_client_challenges SET consumed_at=:now "
                            + "WHERE tenant_id=:tenant AND device_id=:device AND id=:challenge AND " + challengeOwner
                            + " AND consumed_at IS NULL AND created_at<=:now AND expires_at>:now")
                    .param("tenant", tenantId.toString()).param("owner", ownerId).param("device", deviceId.toString())
                    .param("challenge", challengeId.toString()).param("now", Timestamp.from(now)).update() == 1;
        }));
    }

    @Override
    public boolean insertGrant(OperationAuthorizationSnapshot grant) {
        Objects.requireNonNull(grant, "运行授权不能为空");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(grant.tenantId())) return false;
            var device = device(grant.tenantId(), grant.principalId(), grant.deviceId(), true).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE
                    || grant.grantedAt().isBefore(device.createdAt()) || !isOwnedRunningConversation(grant)
                    || grantExists(grant.tenantId(), grant.authorizationId(), grant.runId())) return false;
            String categories = grant.categories().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
            return jdbc.sql("""
                    INSERT INTO local_client_run_grants
                    (tenant_id,id,owner_id,device_id,agent_id,conversation_id,run_id,mode,categories,
                     policy_version,granted_at,expires_at,revoked_at,version)
                    VALUES (:tenant,:id,:owner,:device,:agent,:conversation,:run,:mode,:categories,
                            :policy,:granted,:expires,NULL,1)
                    """)
                    .param("tenant", grant.tenantId().toString()).param("id", grant.authorizationId().toString())
                    .param("owner", grant.principalId()).param("device", grant.deviceId().toString())
                    .param("agent", grant.agentId().toString()).param("conversation", grant.conversationId().toString())
                    .param("run", grant.runId().toString()).param("mode", grant.mode().name())
                    .param("categories", categories).param("policy", grant.policyVersion())
                    .param("granted", Timestamp.from(grant.grantedAt()))
                    .param("expires", Timestamp.from(grant.expiresAt())).update() == 1;
        }));
    }

    @Override
    public Optional<OperationAuthorizationSnapshot> findActiveGrant(
            UUID tenantId, String ownerId, UUID runId, UUID deviceId, Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return jdbc.sql("""
                SELECT g.* FROM local_client_run_grants g
                JOIN local_client_devices d ON d.tenant_id=g.tenant_id AND d.id=g.device_id
                JOIN runs r ON r.tenant_id=g.tenant_id AND r.id=g.run_id AND r.agent_id=g.agent_id
                JOIN conversations c ON c.tenant_id=g.tenant_id AND c.id=g.conversation_id AND c.agent_id=g.agent_id
                WHERE g.tenant_id=:tenant AND g.run_id=:run AND g.device_id=:device
                  AND g.revoked_at IS NULL AND g.granted_at<=:now AND g.expires_at>:now
                  AND d.state='ACTIVE' AND r.status IN ('RUNNING','WAITING_APPROVAL')
                  AND EXISTS (SELECT 1 FROM messages m WHERE m.tenant_id=r.tenant_id
                    AND m.run_id=r.id AND m.conversation_id=c.id AND m.role='USER') AND
                """ + joinedGrantOwner + " AND " + joinedDeviceOwner + " AND " + joinedRunOwner
                        + " AND " + joinedConversationOwner)
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("run", runId.toString())
                .param("device", deviceId.toString()).param("now", Timestamp.from(now))
                .query(this::grant).optional();
    }

    @Override
    public Optional<OperationAuthorizationSnapshot> findActiveGrantByRun(
            UUID tenantId, String ownerId, UUID runId, Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return jdbc.sql("""
                SELECT g.* FROM local_client_run_grants g
                JOIN local_client_devices d ON d.tenant_id=g.tenant_id AND d.id=g.device_id
                JOIN runs r ON r.tenant_id=g.tenant_id AND r.id=g.run_id AND r.agent_id=g.agent_id
                JOIN conversations c ON c.tenant_id=g.tenant_id AND c.id=g.conversation_id AND c.agent_id=g.agent_id
                WHERE g.tenant_id=:tenant AND g.run_id=:run
                  AND g.revoked_at IS NULL AND g.granted_at<=:now AND g.expires_at>:now
                  AND d.state='ACTIVE' AND r.status IN ('RUNNING','WAITING_APPROVAL')
                  AND EXISTS (SELECT 1 FROM messages m WHERE m.tenant_id=r.tenant_id
                    AND m.run_id=r.id AND m.conversation_id=c.id AND m.role='USER') AND
                """ + joinedGrantOwner + " AND " + joinedDeviceOwner + " AND " + joinedRunOwner
                        + " AND " + joinedConversationOwner)
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("run", runId.toString())
                .param("now", Timestamp.from(now)).query(this::grant).optional();
    }

    @Override
    public boolean revokeGrant(UUID tenantId, String ownerId, UUID grantId, Instant now) {
        Objects.requireNonNull(now, "撤销时间不能为空");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            var deviceId = jdbc.sql("SELECT device_id FROM local_client_run_grants "
                            + "WHERE tenant_id=:tenant AND id=:id AND " + grantOwner)
                    .param("tenant", tenantId.toString()).param("id", grantId.toString()).param("owner", ownerId)
                    .query(String.class).optional().map(UUID::fromString).orElse(null);
            if (deviceId == null || device(tenantId, ownerId, deviceId, true).isEmpty()) return false;
            return jdbc.sql("UPDATE local_client_run_grants SET revoked_at=:now,version=version+1 "
                            + "WHERE tenant_id=:tenant AND id=:id AND " + grantOwner
                            + " AND revoked_at IS NULL AND granted_at<=:now")
                    .param("tenant", tenantId.toString()).param("id", grantId.toString()).param("owner", ownerId)
                    .param("now", Timestamp.from(now)).update() == 1;
        }));
    }

    @Override
    public boolean insertPairing(LocalClientPairing pairing) {
        Objects.requireNonNull(pairing, "配对会话不能为空");
        if (pairing.state() != LocalClientPairing.State.PENDING) {
            throw new IllegalArgumentException("只能登记待确认配对");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(pairing.tenantId()) || pairingIdExists(pairing.id())) return false;
            return jdbc.sql("""
                    INSERT INTO local_client_pairing_sessions
                    (tenant_id,id,owner_id,device_name,platform,public_key,nonce,proof,state,
                     device_id,created_at,expires_at,confirmed_at)
                    VALUES (:tenant,:id,:owner,:name,:platform,:publicKey,:nonce,:proof,'PENDING',
                            NULL,:created,:expires,NULL)
                    """)
                    .param("tenant", pairing.tenantId().toString()).param("id", pairing.id().toString())
                    .param("owner", pairing.ownerId()).param("name", pairing.deviceName())
                    .param("platform", pairing.platform().name()).param("publicKey", pairing.publicKey())
                    .param("nonce", Base64.getEncoder().encodeToString(pairing.nonce()))
                    .param("proof", Base64.getEncoder().encodeToString(pairing.proof()))
                    .param("created", Timestamp.from(pairing.createdAt()))
                    .param("expires", Timestamp.from(pairing.expiresAt())).update() == 1;
        }));
    }

    @Override
    public Optional<LocalClientPairing> findPairing(UUID pairingId) {
        Objects.requireNonNull(pairingId, "配对请求标识不能为空");
        return jdbc.sql("SELECT * FROM local_client_pairing_sessions WHERE id=:id")
                .param("id", pairingId.toString()).query(this::pairing).optional();
    }

    @Override
    public boolean confirmPairing(UUID tenantId, String ownerId, UUID pairingId,
                                  LocalClientDevice device, Instant now) {
        Objects.requireNonNull(now, "确认时间不能为空");
        Objects.requireNonNull(device, "设备不能为空");
        if (!tenantId.equals(device.tenantId()) || !ownerId.equals(device.ownerId())
                || device.state() != LocalClientDevice.State.ACTIVE || device.version() != 1) {
            throw new IllegalArgumentException("确认设备与配对归属不一致");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            var current = jdbc.sql("SELECT * FROM local_client_pairing_sessions "
                            + "WHERE tenant_id=:tenant AND id=:id AND " + pairingOwner + " FOR UPDATE")
                    .param("tenant", tenantId.toString()).param("id", pairingId.toString())
                    .param("owner", ownerId).query(this::pairing).optional().orElse(null);
            if (current == null || current.state() != LocalClientPairing.State.PENDING
                    || now.isBefore(current.createdAt()) || !now.isBefore(current.expiresAt())
                    || exists(tenantId, device.id()) || !current.publicKey().equals(device.publicKey())
                    || current.platform() != device.platform() || !current.deviceName().equals(device.displayName())) {
                return false;
            }
            int inserted = jdbc.sql("""
                    INSERT INTO local_client_devices
                    (tenant_id,id,owner_id,display_name,platform,public_key,state,version,created_at,updated_at)
                    VALUES (:tenant,:id,:owner,:name,:platform,:publicKey,'ACTIVE',1,:created,:updated)
                    """)
                    .param("tenant", tenantId.toString()).param("id", device.id().toString())
                    .param("owner", ownerId).param("name", device.displayName())
                    .param("platform", device.platform().name()).param("publicKey", device.publicKey())
                    .param("created", Timestamp.from(device.createdAt()))
                    .param("updated", Timestamp.from(device.updatedAt())).update();
            if (inserted != 1) return false;
            return jdbc.sql("""
                    UPDATE local_client_pairing_sessions
                    SET state='CONFIRMED',device_id=:device,confirmed_at=:now
                    WHERE tenant_id=:tenant AND id=:id AND state='PENDING' AND
                    """ + pairingOwner)
                    .param("tenant", tenantId.toString()).param("id", pairingId.toString())
                    .param("owner", ownerId).param("device", device.id().toString())
                    .param("now", Timestamp.from(now)).update() == 1;
        }));
    }

    @Override
    public boolean expirePairing(UUID tenantId, String ownerId, UUID pairingId, Instant now) {
        Objects.requireNonNull(now, "终止时间不能为空");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            return jdbc.sql("UPDATE local_client_pairing_sessions SET state='EXPIRED' "
                            + "WHERE tenant_id=:tenant AND id=:id AND state='PENDING' AND " + pairingOwner)
                    .param("tenant", tenantId.toString()).param("id", pairingId.toString())
                    .param("owner", ownerId).update() == 1;
        }));
    }

    @Override
    public boolean insertOperation(LocalClientOperation operation) {
        Objects.requireNonNull(operation, "本机操作不能为空");
        if (operation.state() != LocalClientOperation.State.PENDING) {
            throw new IllegalArgumentException("只能登记待派发操作");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(operation.tenantId())) return false;
            var device = device(operation.tenantId(), operation.ownerId(), operation.deviceId(), true).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE
                    || findActiveGrant(operation.tenantId(), operation.ownerId(), operation.runId(),
                    operation.deviceId(), operation.createdAt()).isEmpty()
                    || operationExists(operation.tenantId(), operation.runId(), operation.toolCallId())) {
                return false;
            }
            return jdbc.sql("""
                    INSERT INTO local_client_operations
                    (tenant_id,id,owner_id,device_id,agent_id,conversation_id,run_id,tool_id,tool_call_id,
                     category,request_ciphertext,state,result_ciphertext,safe_error,created_at,expires_at,
                     dispatched_at,completed_at,version)
                    VALUES (:tenant,:id,:owner,:device,:agent,:conversation,:run,:tool,:call,
                            :category,:request,'PENDING','','',:created,:expires,NULL,NULL,1)
                    """)
                    .param("tenant", operation.tenantId().toString()).param("id", operation.id().toString())
                    .param("owner", operation.ownerId()).param("device", operation.deviceId().toString())
                    .param("agent", operation.agentId().toString())
                    .param("conversation", operation.conversationId().toString())
                    .param("run", operation.runId().toString()).param("tool", operation.toolId().toString())
                    .param("call", operation.toolCallId()).param("category", operation.category().name())
                    .param("request", operation.encryptedRequest())
                    .param("created", Timestamp.from(operation.createdAt()))
                    .param("expires", Timestamp.from(operation.expiresAt())).update() == 1;
        }));
    }

    @Override
    public Optional<LocalClientOperation> claimNextOperation(
            UUID tenantId, String ownerId, UUID deviceId, Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return transaction.execute(status -> {
            if (!lockTenant(tenantId)) return Optional.<LocalClientOperation>empty();
            var device = device(tenantId, ownerId, deviceId, true).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE) {
                return Optional.<LocalClientOperation>empty();
            }
            // JOIN 中授权表也含 owner_id，必须显式限定操作表，避免双库将条件判定为歧义列。
            var pending = jdbc.sql("""
                    SELECT o.* FROM local_client_operations o
                    JOIN local_client_run_grants g
                      ON g.tenant_id=o.tenant_id AND g.run_id=o.run_id AND g.device_id=o.device_id
                    JOIN runs r ON r.tenant_id=o.tenant_id AND r.id=o.run_id AND r.agent_id=o.agent_id
                    WHERE o.tenant_id=:tenant AND o.device_id=:device AND o.state='PENDING'
                      AND o.created_at<=:now AND o.expires_at>:now
                      AND g.revoked_at IS NULL AND g.granted_at<=:now AND g.expires_at>:now
                      AND r.status IN ('RUNNING','WAITING_APPROVAL') AND
                    """ + joinedOperationOwner + """
                     ORDER BY o.created_at,o.id LIMIT 1 FOR UPDATE
                    """)
                    .param("tenant", tenantId.toString()).param("owner", ownerId)
                    .param("device", deviceId.toString()).param("now", Timestamp.from(now))
                    .query(this::operation).optional();
            if (pending.isEmpty()) return Optional.empty();
            var row = pending.orElseThrow();
            int updated = jdbc.sql("""
                    UPDATE local_client_operations
                    SET state='DISPATCHED',dispatched_at=:now,version=version+1
                    WHERE tenant_id=:tenant AND id=:id AND state='PENDING' AND version=:version AND
                    """ + operationOwner)
                    .param("tenant", tenantId.toString()).param("id", row.id().toString())
                    .param("owner", ownerId).param("now", Timestamp.from(now))
                    .param("version", row.version()).update();
            return updated == 1
                    ? Optional.of(operation(row.id(), tenantId, ownerId, deviceId).orElseThrow())
                    : Optional.empty();
        });
    }

    @Override
    public Optional<LocalClientOperation> findOperation(
            UUID tenantId, String ownerId, UUID deviceId, UUID operationId) {
        return operation(operationId, tenantId, ownerId, deviceId);
    }

    @Override
    public boolean completeOperation(UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
                                     LocalClientOperation.State state, String encryptedResult, String safeError,
                                     long expectedVersion, Instant now) {
        if (state != LocalClientOperation.State.SUCCEEDED && state != LocalClientOperation.State.FAILED
                && state != LocalClientOperation.State.UNKNOWN) {
            throw new IllegalArgumentException("客户端只能回报成功、失败或未知终态");
        }
        return transitionOperation(operationId, tenantId, ownerId, deviceId, state, encryptedResult,
                safeError, expectedVersion, now, "DISPATCHED");
    }

    @Override
    public boolean markUnknown(UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
                               String safeError, long expectedVersion, Instant now) {
        return transitionOperation(operationId, tenantId, ownerId, deviceId,
                LocalClientOperation.State.UNKNOWN, "", safeError, expectedVersion, now, "DISPATCHED");
    }

    @Override
    public boolean cancelPending(UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
                                 long expectedVersion, Instant now) {
        return transitionOperation(operationId, tenantId, ownerId, deviceId,
                LocalClientOperation.State.CANCELLED, "", "操作已取消", expectedVersion, now, "PENDING");
    }

    private Optional<LocalClientDevice> device(UUID tenant, String owner, UUID id, boolean lock) {
        Objects.requireNonNull(tenant, "租户不能为空");
        Objects.requireNonNull(owner, "主体不能为空");
        Objects.requireNonNull(id, "设备标识不能为空");
        return jdbc.sql("SELECT * FROM local_client_devices WHERE tenant_id=:tenant AND id=:device AND "
                        + deviceOwner + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenant.toString()).param("owner", owner).param("device", id.toString())
                .query(this::device).optional();
    }

    private boolean lockTenant(UUID tenant) {
        return jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant", tenant.toString())
                .query(String.class).optional().isPresent();
    }

    private boolean exists(UUID tenant, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM local_client_devices WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(Long.class).single() != 0;
    }

    private boolean challengeExists(UUID tenant, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM local_client_challenges WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(Long.class).single() != 0;
    }

    private boolean grantExists(UUID tenant, UUID id, UUID run) {
        return jdbc.sql("SELECT COUNT(*) FROM local_client_run_grants WHERE tenant_id=:tenant AND (id=:id OR run_id=:run)")
                .param("tenant", tenant.toString()).param("id", id.toString()).param("run", run.toString())
                .query(Long.class).single() != 0;
    }

    private boolean isOwnedRunningConversation(OperationAuthorizationSnapshot grant) {
        // 会话与运行的关联来自持久化用户消息；不能用请求中的 conversationId 建立跨会话授权。
        return jdbc.sql("""
                SELECT COUNT(*) FROM runs r JOIN conversations c
                  ON c.tenant_id=r.tenant_id AND c.agent_id=r.agent_id
                WHERE r.tenant_id=:tenant AND r.id=:run AND r.agent_id=:agent AND c.id=:conversation
                  AND r.status='RUNNING' AND EXISTS (
                    SELECT 1 FROM messages m WHERE m.tenant_id=r.tenant_id
                      AND m.run_id=r.id AND m.conversation_id=c.id AND m.role='USER'
                  ) AND
                """ + joinedRunOwner + " AND " + joinedConversationOwner)
                .param("tenant", grant.tenantId().toString()).param("owner", grant.principalId())
                .param("run", grant.runId().toString()).param("agent", grant.agentId().toString())
                .param("conversation", grant.conversationId().toString()).query(Long.class).single() != 0;
    }

    private boolean pairingIdExists(UUID pairingId) {
        return jdbc.sql("SELECT COUNT(*) FROM local_client_pairing_sessions WHERE id=:id")
                .param("id", pairingId.toString()).query(Long.class).single() != 0;
    }

    private boolean operationExists(UUID tenantId, UUID runId, String toolCallId) {
        return jdbc.sql("SELECT COUNT(*) FROM local_client_operations "
                        + "WHERE tenant_id=:tenant AND run_id=:run AND tool_call_id=:call")
                .param("tenant", tenantId.toString()).param("run", runId.toString())
                .param("call", toolCallId).query(Long.class).single() != 0;
    }

    private Optional<LocalClientOperation> operation(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId) {
        return jdbc.sql("SELECT * FROM local_client_operations WHERE tenant_id=:tenant AND id=:id "
                        + "AND device_id=:device AND " + operationOwner)
                .param("tenant", tenantId.toString()).param("id", operationId.toString())
                .param("owner", ownerId).param("device", deviceId.toString())
                .query(this::operation).optional();
    }

    /** 终态写入先锁租户和设备，再要求派发版本未变化，避免超时与迟到结果互相覆盖。 */
    private boolean transitionOperation(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            LocalClientOperation.State state, String encryptedResult, String safeError,
            long expectedVersion, Instant now, String expectedState) {
        Objects.requireNonNull(now, "终态时间不能为空");
        if (expectedVersion < 1) throw new IllegalArgumentException("操作版本无效");
        String result = encryptedResult == null ? "" : encryptedResult;
        String error = safeError == null ? "" : safeError;
        if (error.length() > 500) throw new IllegalArgumentException("安全错误原因过长");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(tenantId)) return false;
            var device = device(tenantId, ownerId, deviceId, true).orElse(null);
            if (device == null) return false;
            var current = jdbc.sql("SELECT * FROM local_client_operations "
                            + "WHERE tenant_id=:tenant AND id=:id AND device_id=:device AND "
                            + operationOwner + " FOR UPDATE")
                    .param("tenant", tenantId.toString()).param("id", operationId.toString())
                    .param("owner", ownerId).param("device", deviceId.toString())
                    .query(this::operation).optional().orElse(null);
            if (current == null || !current.state().name().equals(expectedState)
                    || current.version() != expectedVersion
                    || (current.dispatchedAt() != null && now.isBefore(current.dispatchedAt()))) {
                return false;
            }
            return jdbc.sql("""
                    UPDATE local_client_operations
                    SET state=:state,result_ciphertext=:result,safe_error=:error,
                        completed_at=:now,version=version+1
                    WHERE tenant_id=:tenant AND id=:id AND device_id=:device
                      AND state=:expectedState AND version=:version AND
                    """ + operationOwner)
                    .param("tenant", tenantId.toString()).param("id", operationId.toString())
                    .param("device", deviceId.toString()).param("owner", ownerId)
                    .param("state", state.name()).param("result", result).param("error", error)
                    .param("now", Timestamp.from(now)).param("expectedState", expectedState)
                    .param("version", expectedVersion).update() == 1;
        }));
    }

    private LocalClientDevice device(ResultSet row, int rowNumber) throws SQLException {
        return new LocalClientDevice(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                row.getString("owner_id"), row.getString("display_name"),
                LocalClientDevice.Platform.valueOf(row.getString("platform")), row.getString("public_key"),
                LocalClientDevice.State.valueOf(row.getString("state")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private LocalClientChallenge challenge(ResultSet row, int rowNumber) throws SQLException {
        return new LocalClientChallenge(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                row.getString("owner_id"), UUID.fromString(row.getString("device_id")),
                Base64.getDecoder().decode(row.getString("nonce")), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant());
    }

    private LocalClientPairing pairing(ResultSet row, int rowNumber) throws SQLException {
        String deviceId = row.getString("device_id");
        var confirmedAt = row.getTimestamp("confirmed_at");
        return new LocalClientPairing(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                row.getString("device_name"), LocalClientDevice.Platform.valueOf(row.getString("platform")),
                row.getString("public_key"), Base64.getDecoder().decode(row.getString("nonce")),
                Base64.getDecoder().decode(row.getString("proof")),
                LocalClientPairing.State.valueOf(row.getString("state")),
                deviceId == null ? null : UUID.fromString(deviceId), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant());
    }

    private LocalClientOperation operation(ResultSet row, int rowNumber) throws SQLException {
        var dispatchedAt = row.getTimestamp("dispatched_at");
        var completedAt = row.getTimestamp("completed_at");
        return new LocalClientOperation(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("device_id")), UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("conversation_id")), UUID.fromString(row.getString("run_id")),
                UUID.fromString(row.getString("tool_id")), row.getString("tool_call_id"),
                OperationCategory.valueOf(row.getString("category")), row.getString("request_ciphertext"),
                LocalClientOperation.State.valueOf(row.getString("state")), row.getString("result_ciphertext"),
                row.getString("safe_error"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                dispatchedAt == null ? null : dispatchedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant(), row.getLong("version"));
    }

    private OperationAuthorizationSnapshot grant(ResultSet row, int rowNumber) throws SQLException {
        var categories = Arrays.stream(row.getString("categories").split(",", -1))
                .map(OperationCategory::valueOf).collect(Collectors.toUnmodifiableSet());
        return new OperationAuthorizationSnapshot(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("agent_id")), UUID.fromString(row.getString("conversation_id")),
                UUID.fromString(row.getString("run_id")), UUID.fromString(row.getString("device_id")),
                OperationAuthorizationMode.valueOf(row.getString("mode")), categories, row.getLong("policy_version"),
                row.getTimestamp("granted_at").toInstant(), row.getTimestamp("expires_at").toInstant());
    }
}
