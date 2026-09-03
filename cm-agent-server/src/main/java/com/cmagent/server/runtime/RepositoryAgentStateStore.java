package com.cmagent.server.runtime;

import com.cmagent.core.domain.RuntimeCheckpoint;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 将 AgentScope State Store 适配到 CM Agent 的租户隔离加密检查点 Repository。
 *
 * <p>userId 必须采用 {@code tenantId:principalId}，tenant 只从该 Runtime 创建的可信标识解析；
 * 无法解析时直接拒绝，不能落入公共状态槽。序列化明文只存在于当前调用内存，进入 Repository 前
 * 使用随机 IV 的 AES/GCM 加密，日志和异常均不包含载荷。</p>
 */
public final class RepositoryAgentStateStore implements AgentStateStore {
    private final RuntimeCheckpointRepository repository;
    private final ModelCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;

    public RepositoryAgentStateStore(
            RuntimeCheckpointRepository repository,
            ModelCredentialCipher cipher,
            ObjectMapper objectMapper,
            Clock clock,
            Duration ttl
    ) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository 不能为空");
        this.cipher = java.util.Objects.requireNonNull(cipher, "cipher 不能为空");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.clock = java.util.Objects.requireNonNull(clock, "clock 不能为空");
        this.ttl = java.util.Objects.requireNonNull(ttl, "ttl 不能为空");
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        java.util.Objects.requireNonNull(state, "state 不能为空");
        savePayload(userId, sessionId, key, state.getClass().getName(), false, serializeState(state));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        java.util.Objects.requireNonNull(states, "states 不能为空");
        String type = states.isEmpty() ? State.class.getName() : states.getFirst().getClass().getName();
        try {
            savePayload(userId, sessionId, key, type, true, objectMapper.writeValueAsString(states));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("运行检查点序列化失败", exception);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        UUID tenantId = tenantId(userId);
        RuntimeCheckpoint checkpoint = repository.find(tenantId, userId, sessionId, key).orElse(null);
        if (checkpoint == null || checkpoint.listPayload()) {
            return Optional.empty();
        }
        if (!type.getName().equals(checkpoint.stateType()) || expired(checkpoint)) {
            return Optional.empty();
        }
        try {
            String json = cipher.decrypt(checkpoint.encryptedPayload());
            if (type == AgentState.class) {
                return Optional.of(type.cast(AgentState.fromJsonString(json)));
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("运行检查点不可用", exception);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        UUID tenantId = tenantId(userId);
        RuntimeCheckpoint checkpoint = repository.find(tenantId, userId, sessionId, key).orElse(null);
        if (checkpoint == null || !checkpoint.listPayload() || expired(checkpoint)) {
            return List.of();
        }
        if (!State.class.getName().equals(checkpoint.stateType()) && !type.getName().equals(checkpoint.stateType())) {
            return List.of();
        }
        try {
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, type);
            return List.copyOf(objectMapper.readValue(cipher.decrypt(checkpoint.encryptedPayload()), listType));
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("运行检查点不可用", exception);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return repository.exists(tenantId(userId), userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        repository.deleteSession(tenantId(userId), userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        repository.delete(tenantId(userId), userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return repository.listSessionIds(tenantId(userId), userId);
    }

    private void savePayload(
            String userId, String sessionId, String key, String stateType, boolean listPayload, String plaintext) {
        UUID tenantId = tenantId(userId);
        Instant now = clock.instant();
        RuntimeCheckpoint previous = repository.find(tenantId, userId, sessionId, key).orElse(null);
        repository.save(new RuntimeCheckpoint(
                previous == null ? UUID.randomUUID() : previous.id(), tenantId, userId, sessionId, key,
                stateType, listPayload, cipher.encrypt(plaintext), now.plus(ttl),
                previous == null ? now : previous.createdAt(), now));
    }

    private String serializeState(State state) {
        try {
            return state instanceof AgentState agentState
                    ? agentState.toJson()
                    : objectMapper.writeValueAsString(state);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("运行检查点序列化失败", exception);
        }
    }

    private boolean expired(RuntimeCheckpoint checkpoint) {
        if (clock.instant().isBefore(checkpoint.expiresAt())) {
            return false;
        }
        repository.delete(checkpoint.tenantId(), checkpoint.userId(), checkpoint.sessionId(), checkpoint.stateKey());
        return true;
    }

    private static UUID tenantId(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("AgentScope userId 不能为空");
        }
        int separator = userId.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("AgentScope userId 缺少可信租户前缀");
        }
        try {
            return UUID.fromString(userId.substring(0, separator));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AgentScope userId 的租户前缀不合法", exception);
        }
    }
}
