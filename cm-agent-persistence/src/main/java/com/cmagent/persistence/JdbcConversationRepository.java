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

/**
 * 使用 JDBC 保存和查询租户内 Agent 会话。
 *
 * <p>所有查询都同时限制租户；读取单条会话时额外限制 Agent，避免仅凭会话 UUID 跨 Agent 或跨租户读取。
 * 列表使用 {@code updated_at + id} 复合游标，保证数据库排序与 Core 分页语义一致。</p>
 */
public class JdbcConversationRepository implements ConversationRepository {
    @Override public boolean supportsWorkspaceProtocol() { return true; }

    /** 直接读取原会话列，不从可能丢失的绑定行推断；缺失和非法协议都不能降为0。 */
    @Override public int workspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId) {
        int protocol = jdbcClient.sql("SELECT workspace_protocol FROM conversations WHERE tenant_id=:tenant AND agent_id=:agent AND id=:id")
                .param("tenant", tenantId.toString()).param("agent", agentId.toString()).param("id", conversationId.toString())
                .query(Integer.class).optional().orElseThrow(() -> new NoSuchElementException("会话不存在"));
        if (protocol != 0 && protocol != 3) throw new IllegalStateException("会话来源见证不合法");
        return protocol;
    }

    /** 只更新来源头；空会话、版本及控制锁由宿主验证，不能通过此方法重置历史内容来改绑。 */
    @Override public void updateWorkspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId, int protocol) {
        requireProtocol(protocol);
        int updated = jdbcClient.sql("UPDATE conversations SET workspace_protocol=:protocol WHERE tenant_id=:tenant AND agent_id=:agent AND id=:id")
                .param("protocol", protocol).param("tenant", tenantId.toString()).param("agent", agentId.toString())
                .param("id", conversationId.toString()).update();
        // MySQL连接可按实际变更数返回0；重复写相同见证合法，但不存在不能被当作更新成功。
        if (updated == 0 && workspaceProtocol(tenantId, agentId, conversationId) != protocol)
            throw new NoSuchElementException("会话来源见证未能更新");
    }

    /** owner条件在游标和LIMIT前执行；原三态仍可按原租户权限读取，不扩大为全部会话私有。 */
    @Override public List<Conversation> listVisible(UUID tenantId, UUID agentId, String principalId, ConversationPageRequest page) {
        String owner = JdbcOwnerPredicate.equal(jdbcClient, "b.principal_id", ":principal");
        String creator = JdbcOwnerPredicate.equal(jdbcClient, "c.created_by", ":principal");
        var query = jdbcClient.sql("""
                SELECT c.id,c.tenant_id,c.agent_id,c.title,c.created_by,c.created_at,c.updated_at FROM conversations c
                WHERE c.tenant_id=:tenant AND c.agent_id=:agent
                AND (c.workspace_protocol=0 OR
                """ + creator + ") "
                + """
                AND NOT EXISTS (SELECT 1 FROM conversation_workspace_bindings b WHERE b.tenant_id=c.tenant_id
                  AND b.agent_id=c.agent_id AND b.resource_id=c.id AND b.selection_mode='IMPORTED'
                  AND NOT (
                """ + owner + " AND " + creator + "))"
                + " AND NOT EXISTS (SELECT 1 FROM messages m JOIN runs r ON r.tenant_id=m.tenant_id AND r.id=m.run_id"
                + " WHERE m.tenant_id=c.tenant_id AND m.conversation_id=c.id AND r.workspace_protocol=3 AND NOT ("
                + JdbcOwnerPredicate.equal(jdbcClient,"r.principal_id",":principal")+" AND "+creator+"))"
                + (page.beforeUpdatedAt() == null ? "" : " AND (c.updated_at<:before OR (c.updated_at=:before AND c.id<:beforeId))")
                + " ORDER BY c.updated_at DESC,c.id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("agent", agentId.toString()).param("principal", principalId).param("limit", page.limit());
        if (page.beforeUpdatedAt() != null) query = query.param("before", Timestamp.from(page.beforeUpdatedAt())).param("beforeId", page.beforeId().toString());
        return query.query(this::mapConversation).list();
    }
    /** 同一事务内持有数据库行锁，跨实例也不能在首发创建 Run 的同时修改目录。 */
    @Override public Optional<Conversation> lock(UUID tenantId, UUID agentId, UUID conversationId) {
        return jdbcClient.sql("SELECT * FROM conversations WHERE tenant_id=:tenant AND agent_id=:agent AND id=:id FOR UPDATE")
                .param("tenant",tenantId.toString()).param("agent",agentId.toString()).param("id",conversationId.toString())
                .query(this::mapConversation).optional();
    }
    /** 消息与会话同时限制 tenant，避免仅凭 runId 把另一租户关联进当前下载。 */
    @Override
    public Optional<Conversation> findByRun(UUID tenantId,UUID runId) {
        return jdbcClient.sql("""
            SELECT DISTINCT c.id,c.tenant_id,c.agent_id,c.title,c.created_by,c.created_at,c.updated_at
            FROM conversations c JOIN messages m ON m.conversation_id=c.id AND m.tenant_id=c.tenant_id
            WHERE c.tenant_id=:tenant AND m.run_id=:run
            """).param("tenant",tenantId.toString()).param("run",runId.toString()).query(this::mapConversation).optional();
    }
    private final JdbcClient jdbcClient;

    /**
     * 创建会话 JDBC 仓储。
     *
     * @param jdbcClient 执行具名参数 SQL 的客户端
     */
    public JdbcConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    /**
     * 保存新会话，并在写入前拒绝参数租户与领域对象不一致的调用。
     */
    public Conversation save(UUID tenantId, Conversation conversation) {
        return saveWithWorkspaceProtocol(tenantId, conversation, 0);
    }

    /** 与首次会话INSERT一次提交来源头，绑定行丢失后仍能识别尚未首发的私有会话。 */
    @Override public Conversation saveWithWorkspaceProtocol(UUID tenantId, Conversation conversation, int protocol) {
        requireTenant(tenantId, conversation.tenantId());
        requireProtocol(protocol);
        jdbcClient.sql("""
                        INSERT INTO conversations (
                            id, tenant_id, agent_id, title, created_by, created_at, updated_at, workspace_protocol
                        ) VALUES (
                            :id, :tenantId, :agentId, :title, :createdBy, :createdAt, :updatedAt, :protocol
                        )
                        """)
                .param("id", conversation.id().toString())
                .param("tenantId", tenantId.toString())
                .param("agentId", conversation.agentId().toString())
                .param("title", conversation.title())
                .param("createdBy", conversation.createdBy())
                .param("createdAt", Timestamp.from(conversation.createdAt()))
                .param("updatedAt", Timestamp.from(conversation.updatedAt()))
                .param("protocol", protocol)
                .update();
        return conversation;
    }

    @Override
    /**
     * 以租户、Agent 和会话标识三重条件读取会话，使不存在和越权读取统一表现为空结果。
     */
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
    /**
     * 使用与 API 游标一致的复合排序分页读取会话。
     *
     * <p>当多个会话拥有相同 {@code updated_at} 时，UUID 字符串顺序作为稳定的次级排序，避免翻页重复。</p>
     */
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
    /**
     * 更新标题和最后活动时间，并重新读取持久化结果。
     *
     * <p>更新条件始终包含可信租户；影响行数不是一时拒绝访问，说明会话已不存在或不属于当前租户。</p>
     */
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

    private static void requireProtocol(int protocol) {
        if (protocol != 0 && protocol != 3) throw new IllegalArgumentException("会话工作区协议不合法");
    }
}
