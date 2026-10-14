package com.cmagent.server.store;

import com.cmagent.core.domain.RuntimeCheckpoint;
import com.cmagent.core.repository.RuntimeCheckpointRepository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 仅供 memory profile 和测试使用的进程内加密检查点仓储。 */
public final class InMemoryRuntimeCheckpointRepository implements RuntimeCheckpointRepository {
    private final Map<Key, RuntimeCheckpoint> checkpoints = new ConcurrentHashMap<>();

    /** 系统排空探测只返回有无状态；过期或孤立条目也必须先由既有清理流程收口。 */
    public boolean hasAnyCheckpoints() { return !checkpoints.isEmpty(); }

    @Override
    public RuntimeCheckpoint save(RuntimeCheckpoint checkpoint) {
        checkpoints.put(new Key(checkpoint.tenantId(), checkpoint.userId(), checkpoint.sessionId(), checkpoint.stateKey()),
                checkpoint);
        return checkpoint;
    }

    @Override
    public Optional<RuntimeCheckpoint> find(UUID tenantId, String userId, String sessionId, String stateKey) {
        return Optional.ofNullable(checkpoints.get(new Key(tenantId, userId, sessionId, stateKey)));
    }

    @Override
    public boolean exists(UUID tenantId, String userId, String sessionId) {
        return checkpoints.keySet().stream().anyMatch(key -> key.tenantId.equals(tenantId)
                && key.userId.equals(userId) && key.sessionId.equals(sessionId));
    }

    @Override
    public void deleteSession(UUID tenantId, String userId, String sessionId) {
        checkpoints.keySet().removeIf(key -> key.tenantId.equals(tenantId)
                && key.userId.equals(userId) && key.sessionId.equals(sessionId));
    }

    @Override
    public void delete(UUID tenantId, String userId, String sessionId, String stateKey) {
        checkpoints.remove(new Key(tenantId, userId, sessionId, stateKey));
    }

    @Override
    public Set<String> listSessionIds(UUID tenantId, String userId) {
        return checkpoints.keySet().stream()
                .filter(key -> key.tenantId.equals(tenantId) && key.userId.equals(userId))
                .map(Key::sessionId).collect(Collectors.toUnmodifiableSet());
    }

    private record Key(UUID tenantId, String userId, String sessionId, String stateKey) {
    }
}
