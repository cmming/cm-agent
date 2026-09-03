package com.cmagent.persistence;

import com.cmagent.core.domain.RuntimeCheckpoint;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 使用 tenant + user + session + key 唯一槽保存已加密 AgentScope 状态。 */
public final class JdbcRuntimeCheckpointRepository implements RuntimeCheckpointRepository {
    private final JdbcClient jdbcClient;

    public JdbcRuntimeCheckpointRepository(JdbcClient jdbcClient) {
        this.jdbcClient = java.util.Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public RuntimeCheckpoint save(RuntimeCheckpoint checkpoint) {
        int updated = jdbcClient.sql("""
                        UPDATE runtime_checkpoints
                        SET state_type = :stateType, list_payload = :listPayload,
                            encrypted_payload = :payload, expires_at = :expiresAt, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND session_id = :sessionId AND state_key = :stateKey
                        """)
                .param("stateType", checkpoint.stateType())
                .param("listPayload", checkpoint.listPayload())
                .param("payload", checkpoint.encryptedPayload())
                .param("expiresAt", Timestamp.from(checkpoint.expiresAt()))
                .param("updatedAt", Timestamp.from(checkpoint.updatedAt()))
                .param("tenantId", checkpoint.tenantId().toString())
                .param("userId", checkpoint.userId())
                .param("sessionId", checkpoint.sessionId())
                .param("stateKey", checkpoint.stateKey())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO runtime_checkpoints (
                                id, tenant_id, user_id, session_id, state_key, state_type,
                                list_payload, encrypted_payload, expires_at, created_at, updated_at)
                            VALUES (:id, :tenantId, :userId, :sessionId, :stateKey, :stateType,
                                    :listPayload, :payload, :expiresAt, :createdAt, :updatedAt)
                            """)
                    .param("id", checkpoint.id().toString())
                    .param("tenantId", checkpoint.tenantId().toString())
                    .param("userId", checkpoint.userId())
                    .param("sessionId", checkpoint.sessionId())
                    .param("stateKey", checkpoint.stateKey())
                    .param("stateType", checkpoint.stateType())
                    .param("listPayload", checkpoint.listPayload())
                    .param("payload", checkpoint.encryptedPayload())
                    .param("expiresAt", Timestamp.from(checkpoint.expiresAt()))
                    .param("createdAt", Timestamp.from(checkpoint.createdAt()))
                    .param("updatedAt", Timestamp.from(checkpoint.updatedAt()))
                    .update();
        }
        return find(checkpoint.tenantId(), checkpoint.userId(), checkpoint.sessionId(), checkpoint.stateKey())
                .orElseThrow();
    }

    @Override
    public Optional<RuntimeCheckpoint> find(
            UUID tenantId, String userId, String sessionId, String stateKey) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, user_id, session_id, state_key, state_type,
                               list_payload, encrypted_payload, expires_at, created_at, updated_at
                        FROM runtime_checkpoints
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND session_id = :sessionId AND state_key = :stateKey
                        """)
                .param("tenantId", tenantId.toString())
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("stateKey", stateKey)
                .query(this::map)
                .optional();
    }

    @Override
    public boolean exists(UUID tenantId, String userId, String sessionId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM runtime_checkpoints
                        WHERE tenant_id = :tenantId AND user_id = :userId AND session_id = :sessionId
                        """)
                .param("tenantId", tenantId.toString()).param("userId", userId).param("sessionId", sessionId)
                .query(Integer.class).single() > 0;
    }

    @Override
    public void deleteSession(UUID tenantId, String userId, String sessionId) {
        jdbcClient.sql("""
                        DELETE FROM runtime_checkpoints
                        WHERE tenant_id = :tenantId AND user_id = :userId AND session_id = :sessionId
                        """)
                .param("tenantId", tenantId.toString()).param("userId", userId).param("sessionId", sessionId)
                .update();
    }

    @Override
    public void delete(UUID tenantId, String userId, String sessionId, String stateKey) {
        jdbcClient.sql("""
                        DELETE FROM runtime_checkpoints
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND session_id = :sessionId AND state_key = :stateKey
                        """)
                .param("tenantId", tenantId.toString()).param("userId", userId)
                .param("sessionId", sessionId).param("stateKey", stateKey).update();
    }

    @Override
    public Set<String> listSessionIds(UUID tenantId, String userId) {
        return jdbcClient.sql("""
                        SELECT DISTINCT session_id FROM runtime_checkpoints
                        WHERE tenant_id = :tenantId AND user_id = :userId
                        """)
                .param("tenantId", tenantId.toString()).param("userId", userId)
                .query(String.class).list().stream().collect(Collectors.toUnmodifiableSet());
    }

    private RuntimeCheckpoint map(ResultSet rs, int row) throws SQLException {
        return new RuntimeCheckpoint(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id")),
                rs.getString("user_id"), rs.getString("session_id"), rs.getString("state_key"),
                rs.getString("state_type"), rs.getBoolean("list_payload"), rs.getString("encrypted_payload"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
