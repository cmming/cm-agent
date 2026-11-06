package com.cmagent.server.store;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.domain.LocalClientPairing;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.repository.LocalClientOperationRepository;
import com.cmagent.core.repository.LocalClientPairingRepository;
import com.cmagent.core.repository.LocalClientRunGrantRepository;
import com.cmagent.core.security.OperationAuthorizationSnapshot;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地与测试模式使用的内存设备、配对、挑战、单轮授权和操作队列。
 *
 * <p>所有状态由同一个监视器保护，测试中的领取、结果登记与撤销竞争因此具有原子性。这里不提供
 * 重启恢复，生产必须改用 {@code JdbcLocalClientDeviceRepository} 并保持相同租户和主体边界。</p>
 */
public final class InMemoryLocalClientAuthorizationRepository implements LocalClientDeviceRepository,
        LocalClientRunGrantRepository, LocalClientPairingRepository, LocalClientOperationRepository {
    private final Map<Key, LocalClientDevice> devices = new HashMap<>();
    private final Map<UUID, LocalClientDevice> devicesById = new HashMap<>();
    private final Map<Key, LocalClientChallenge> challenges = new HashMap<>();
    private final Map<Key, Instant> consumedChallenges = new HashMap<>();
    private final Map<Key, OperationAuthorizationSnapshot> grants = new HashMap<>();
    private final Map<UUID, LocalClientPairing> pairings = new HashMap<>();
    private final Map<Key, LocalClientOperation> operations = new HashMap<>();

    @Override
    public synchronized boolean insert(LocalClientDevice device) {
        if (device == null || device.state() != LocalClientDevice.State.ACTIVE || device.version() != 1) {
            throw new IllegalArgumentException("设备只能以初始活动版本登记");
        }
        Key key = new Key(device.tenantId(), device.id());
        if (devicesById.containsKey(device.id())) return false;
        devices.put(key, device);
        devicesById.put(device.id(), device);
        return true;
    }

    @Override
    public synchronized Optional<LocalClientDevice> find(UUID tenantId, String ownerId, UUID deviceId) {
        LocalClientDevice device = devices.get(new Key(tenantId, deviceId));
        // 使用 String.equals，不能替换为数据库默认大小写或尾空格比较规则。
        return device != null && device.ownerId().equals(ownerId) ? Optional.of(device) : Optional.empty();
    }

    @Override
    public synchronized List<LocalClientDevice> listByOwner(UUID tenantId, String ownerId) {
        return devices.values().stream()
                .filter(device -> device.tenantId().equals(tenantId) && device.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(LocalClientDevice::createdAt).reversed()
                        .thenComparing(LocalClientDevice::id))
                .toList();
    }

    @Override
    public synchronized Optional<LocalClientDevice> findAnyById(UUID deviceId) {
        return Optional.ofNullable(devicesById.get(deviceId));
    }

    @Override
    public synchronized boolean revoke(
            UUID tenantId, String ownerId, UUID deviceId, long expectedVersion, Instant now) {
        LocalClientDevice current = find(tenantId, ownerId, deviceId).orElse(null);
        if (current == null || current.state() != LocalClientDevice.State.ACTIVE
                || current.version() != expectedVersion || now.isBefore(current.updatedAt())) {
            return false;
        }
        var revoked = new LocalClientDevice(current.id(), current.tenantId(), current.ownerId(),
                current.displayName(), current.platform(), current.publicKey(),
                LocalClientDevice.State.REVOKED, current.version() + 1, current.createdAt(), now);
        devices.put(new Key(tenantId, deviceId), revoked);
        devicesById.put(deviceId, revoked);
        return true;
    }

    @Override
    public synchronized boolean insertChallenge(LocalClientChallenge challenge) {
        LocalClientDevice device = devices.get(new Key(challenge.tenantId(), challenge.deviceId()));
        Key key = new Key(challenge.tenantId(), challenge.id());
        if (device == null || !device.ownerId().equals(challenge.ownerId())
                || device.state() != LocalClientDevice.State.ACTIVE || challenges.containsKey(key)
                || challenge.createdAt().isBefore(device.createdAt())) {
            return false;
        }
        challenges.put(key, challenge);
        return true;
    }

    @Override
    public synchronized Optional<LocalClientChallenge> findChallenge(
            UUID tenantId, String ownerId, UUID deviceId, UUID challengeId, Instant now) {
        Key key = new Key(tenantId, challengeId);
        LocalClientChallenge challenge = challenges.get(key);
        if (challenge == null || !challenge.ownerId().equals(ownerId)
                || !challenge.deviceId().equals(deviceId) || consumedChallenges.containsKey(key)
                || now.isBefore(challenge.createdAt()) || !now.isBefore(challenge.expiresAt())) {
            return Optional.empty();
        }
        LocalClientDevice device = devices.get(new Key(tenantId, deviceId));
        return device != null && device.ownerId().equals(ownerId)
                && device.state() == LocalClientDevice.State.ACTIVE
                ? Optional.of(challenge) : Optional.empty();
    }

    @Override
    public synchronized boolean consumeChallenge(
            UUID tenantId, String ownerId, UUID deviceId, UUID challengeId, Instant now) {
        if (findChallenge(tenantId, ownerId, deviceId, challengeId, now).isEmpty()) return false;
        consumedChallenges.put(new Key(tenantId, challengeId), now);
        return true;
    }

    @Override
    public synchronized boolean insertGrant(OperationAuthorizationSnapshot grant) {
        LocalClientDevice device = devices.get(new Key(grant.tenantId(), grant.deviceId()));
        boolean duplicate = grants.values().stream().anyMatch(existing ->
                existing.tenantId().equals(grant.tenantId()) && existing.runId().equals(grant.runId()));
        if (device == null || !device.ownerId().equals(grant.principalId())
                || device.state() != LocalClientDevice.State.ACTIVE || grant.grantedAt().isBefore(device.createdAt())
                || duplicate) {
            return false;
        }
        grants.put(new Key(grant.tenantId(), grant.authorizationId()), grant);
        return true;
    }

    @Override
    public synchronized Optional<OperationAuthorizationSnapshot> findActiveGrant(
            UUID tenantId, String ownerId, UUID runId, UUID deviceId, Instant now) {
        return grants.values().stream()
                .filter(grant -> grant.tenantId().equals(tenantId) && grant.principalId().equals(ownerId)
                        && grant.runId().equals(runId) && grant.deviceId().equals(deviceId)
                        && grant.isWithinTimeWindow(now))
                .filter(grant -> {
                    LocalClientDevice device = devices.get(new Key(tenantId, deviceId));
                    return device != null && device.ownerId().equals(ownerId)
                            && device.state() == LocalClientDevice.State.ACTIVE;
                })
                .findFirst();
    }

    @Override
    public synchronized Optional<OperationAuthorizationSnapshot> findActiveGrantByRun(
            UUID tenantId, String ownerId, UUID runId, Instant now) {
        return grants.values().stream()
                .filter(grant -> grant.tenantId().equals(tenantId) && grant.principalId().equals(ownerId)
                        && grant.runId().equals(runId) && grant.isWithinTimeWindow(now))
                .filter(grant -> {
                    LocalClientDevice device = devices.get(new Key(tenantId, grant.deviceId()));
                    return device != null && device.ownerId().equals(ownerId)
                            && device.state() == LocalClientDevice.State.ACTIVE;
                })
                .findFirst();
    }

    @Override
    public synchronized boolean revokeGrant(UUID tenantId, String ownerId, UUID grantId, Instant now) {
        Key key = new Key(tenantId, grantId);
        OperationAuthorizationSnapshot grant = grants.get(key);
        // 必须先核对可信主体和有效期，不能让其他主体仅凭授权标识撤销他人的授权。
        if (grant == null || !grant.principalId().equals(ownerId)
                || now.isBefore(grant.grantedAt()) || !now.isBefore(grant.expiresAt())) return false;
        grants.remove(key);
        return true;
    }

    @Override
    public synchronized boolean insertPairing(LocalClientPairing pairing) {
        if (pairing.state() != LocalClientPairing.State.PENDING || pairings.containsKey(pairing.id())) return false;
        pairings.put(pairing.id(), pairing);
        return true;
    }

    @Override
    public synchronized Optional<LocalClientPairing> findPairing(UUID pairingId) {
        return Optional.ofNullable(pairings.get(pairingId));
    }

    @Override
    public synchronized boolean confirmPairing(
            UUID tenantId, String ownerId, UUID pairingId, LocalClientDevice device, Instant now) {
        LocalClientPairing pairing = pairings.get(pairingId);
        if (pairing == null || pairing.state() != LocalClientPairing.State.PENDING
                || !pairing.tenantId().equals(tenantId) || !pairing.ownerId().equals(ownerId)
                || !pairing.publicKey().equals(device.publicKey()) || pairing.platform() != device.platform()
                || !pairing.deviceName().equals(device.displayName())
                || now.isBefore(pairing.createdAt()) || !now.isBefore(pairing.expiresAt()) || !insert(device)) {
            return false;
        }
        pairings.put(pairingId, new LocalClientPairing(pairing.id(), pairing.tenantId(), pairing.ownerId(),
                pairing.deviceName(), pairing.platform(), pairing.publicKey(), pairing.nonce(), pairing.proof(),
                LocalClientPairing.State.CONFIRMED, device.id(), pairing.createdAt(), pairing.expiresAt(), now));
        return true;
    }

    @Override
    public synchronized boolean expirePairing(
            UUID tenantId, String ownerId, UUID pairingId, Instant now) {
        LocalClientPairing pairing = pairings.get(pairingId);
        if (pairing == null || pairing.state() != LocalClientPairing.State.PENDING
                || !pairing.tenantId().equals(tenantId) || !pairing.ownerId().equals(ownerId)) {
            return false;
        }
        pairings.put(pairingId, new LocalClientPairing(pairing.id(), pairing.tenantId(), pairing.ownerId(),
                pairing.deviceName(), pairing.platform(), pairing.publicKey(), pairing.nonce(), pairing.proof(),
                LocalClientPairing.State.EXPIRED, null, pairing.createdAt(), pairing.expiresAt(), null));
        return true;
    }

    @Override
    public synchronized boolean insertOperation(LocalClientOperation operation) {
        Key key = new Key(operation.tenantId(), operation.id());
        boolean duplicate = operations.values().stream().anyMatch(existing ->
                existing.tenantId().equals(operation.tenantId()) && existing.runId().equals(operation.runId())
                        && existing.toolCallId().equals(operation.toolCallId()));
        if (operation.state() != LocalClientOperation.State.PENDING || operations.containsKey(key) || duplicate
                || findActiveGrant(operation.tenantId(), operation.ownerId(), operation.runId(),
                operation.deviceId(), operation.createdAt()).isEmpty()) {
            return false;
        }
        operations.put(key, operation);
        return true;
    }

    @Override
    public synchronized Optional<LocalClientOperation> claimNextOperation(
            UUID tenantId, String ownerId, UUID deviceId, Instant now) {
        LocalClientDevice device = devices.get(new Key(tenantId, deviceId));
        if (device == null || !device.ownerId().equals(ownerId)
                || device.state() != LocalClientDevice.State.ACTIVE) {
            return Optional.empty();
        }
        LocalClientOperation pending = operations.values().stream()
                .filter(operation -> operation.tenantId().equals(tenantId)
                        && operation.ownerId().equals(ownerId) && operation.deviceId().equals(deviceId)
                        && operation.state() == LocalClientOperation.State.PENDING
                        && !operation.createdAt().isAfter(now) && operation.expiresAt().isAfter(now))
                .filter(operation -> findActiveGrant(tenantId, ownerId, operation.runId(), deviceId, now).isPresent())
                .min(Comparator.comparing(LocalClientOperation::createdAt)
                        .thenComparing(LocalClientOperation::id))
                .orElse(null);
        if (pending == null) return Optional.empty();
        LocalClientOperation dispatched = new LocalClientOperation(pending.id(), pending.tenantId(),
                pending.ownerId(), pending.deviceId(), pending.agentId(), pending.conversationId(),
                pending.runId(), pending.toolId(), pending.toolCallId(), pending.category(),
                pending.encryptedRequest(), LocalClientOperation.State.DISPATCHED, "", "",
                pending.createdAt(), pending.expiresAt(), now, null, pending.version() + 1);
        operations.put(new Key(tenantId, pending.id()), dispatched);
        return Optional.of(dispatched);
    }

    @Override
    public synchronized Optional<LocalClientOperation> findOperation(
            UUID tenantId, String ownerId, UUID deviceId, UUID operationId) {
        LocalClientOperation operation = operations.get(new Key(tenantId, operationId));
        return operation != null && operation.ownerId().equals(ownerId) && operation.deviceId().equals(deviceId)
                ? Optional.of(operation) : Optional.empty();
    }

    @Override
    public synchronized boolean completeOperation(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            LocalClientOperation.State state, String encryptedResult, String safeError,
            long expectedVersion, Instant now) {
        if (state != LocalClientOperation.State.SUCCEEDED && state != LocalClientOperation.State.FAILED
                && state != LocalClientOperation.State.UNKNOWN) {
            throw new IllegalArgumentException("客户端只能回报成功、失败或未知终态");
        }
        return transition(operationId, tenantId, ownerId, deviceId, state,
                encryptedResult, safeError, expectedVersion, now);
    }

    @Override
    public synchronized boolean markUnknown(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            String safeError, long expectedVersion, Instant now) {
        return transition(operationId, tenantId, ownerId, deviceId,
                LocalClientOperation.State.UNKNOWN, "", safeError, expectedVersion, now);
    }

    @Override
    public synchronized boolean cancelPending(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            long expectedVersion, Instant now) {
        return transition(operationId, tenantId, ownerId, deviceId,
                LocalClientOperation.State.CANCELLED, "", "操作已取消", expectedVersion, now);
    }

    private boolean transition(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            LocalClientOperation.State state, String encryptedResult, String safeError,
            long expectedVersion, Instant now) {
        LocalClientOperation current = findOperation(tenantId, ownerId, deviceId, operationId).orElse(null);
        if (current == null || (state == LocalClientOperation.State.CANCELLED
                ? current.state() != LocalClientOperation.State.PENDING
                : current.state() != LocalClientOperation.State.DISPATCHED)
                || current.version() != expectedVersion) {
            return false;
        }
        LocalClientOperation completed = new LocalClientOperation(current.id(), current.tenantId(),
                current.ownerId(), current.deviceId(), current.agentId(), current.conversationId(),
                current.runId(), current.toolId(), current.toolCallId(), current.category(),
                current.encryptedRequest(), state, encryptedResult == null ? "" : encryptedResult,
                safeError == null ? "" : safeError, current.createdAt(), current.expiresAt(),
                current.dispatchedAt(), now, current.version() + 1);
        operations.put(new Key(tenantId, operationId), completed);
        return true;
    }

    private record Key(UUID tenantId, UUID resourceId) { }
}
