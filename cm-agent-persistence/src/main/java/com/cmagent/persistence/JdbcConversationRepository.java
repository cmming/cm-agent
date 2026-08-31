package com.cmagent.persistence;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.repository.ConversationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 保存和查询租户内 Agent 会话。 */
public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcClient jdbcClient;

    public JdbcConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public Conversation save(UUID tenantId, Conversation conversation) {
        requireTenant(tenantId, conversation.tenantId());
        jdbcClient.sql("""
                        INSERT INTO conversations (
                            id, tenant_id, agent_id, title, created_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, :agentId, :title, :createdBy, :createdAt, :updatedAt
                        )
                        """)
                .param("id", conversation.id().toString())
                .param("tenantId", tenantId.toString())
                .param("agentId", conversation.agentId().toString())
                .param("title", conversation.title())
                .param("createdBy", conversation.createdBy())
                .param("createdAt", Timestamp.from(conversation.createdAt()))
                .param("updatedAt", Timestamp.from(conversation.updatedAt()))
                .update();
        return conversation;
    }

    @Override
    public Optional<Conversation> findByTenantAndAgentAndId(
            UUID tenantId, UUID agentId, UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, title, created_by, created_at, updated_at
                        FROM conversations
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND id = :conversationId
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString())
                .query(this::mapConversation)
                .optional();
    }

    @Override
    public List<Conversation> listByTenantAndAgent(
            UUID tenantId, UUID agentId, ConversationPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        if (pageRequest.beforeUpdatedAt() == null) {
            return jdbcClient.sql("""
                            SELECT id, tenant_id, agent_id, title, created_by, created_at, updated_at
                            FROM conversations
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                            ORDER BY updated_at DESC, id DESC
                            LIMIT :limit
                            """)
                    .param("tenantId", tenantId.toString())
                    .param("agentId", agentId.toString())
                    .param("limit", pageRequest.limit())
                    .query(this::mapConversation)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, title, created_by, created_at, updated_at
                        FROM conversations
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                          AND (updated_at < :beforeUpdatedAt
                               OR (updated_at = :beforeUpdatedAt AND id < :beforeId))
                        ORDER BY updated_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("beforeUpdatedAt", Timestamp.from(pageRequest.beforeUpdatedAt()))
                .param("beforeId", pageRequest.beforeId().toString())
                .param("limit", pageRequest.limit())
                .query(this::mapConversation)
                .list();
    }

    @Override
    public Conversation touch(UUID tenantId, UUID conversationId, String title, Instant updatedAt) {
        int updated = jdbcClient.sql("""
                        UPDATE conversations
                        SET title = :title, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :conversationId
                        """)
                .param("title", title)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("tenantId", tenantId.toString())
                .param("conversationId", conversationId.toString())
                .update();
        if (updated != 1) {
            throw new NoSuchElementException("会话不存在");
        }
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, title, created_by, created_at, updated_at
                        FROM conversations
                        WHERE tenant_id = :tenantId AND id = :conversationId
                        """)
                .param("tenantId", tenantId.toString())
                .param("conversationId", conversationId.toString())
                .query(this::mapConversation)
                .single();
    }

    private Conversation mapConversation(ResultSet resultSet, int rowNum) throws SQLException {
        return new Conversation(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                resultSet.getString("title"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static void requireTenant(UUID expected, UUID actual) {
        if (!Objects.requireNonNull(expected, "tenantId 不能为空").equals(actual)) {
            throw new IllegalArgumentException("tenantId 与会话不匹配");
        }
    }
}
