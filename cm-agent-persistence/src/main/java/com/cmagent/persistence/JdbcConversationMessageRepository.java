package com.cmagent.persistence;

import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用会话行锁为 JDBC 消息分配稳定序号。
 *
 * <p>先锁定 {@code conversations} 行，再计算并写入下一条消息，使 {@code MAX(sequence_no) + 1}
 * 在同一会话内仍然安全；锁只覆盖短暂的持久化事务，绝不覆盖模型调用。</p>
 */
public class JdbcConversationMessageRepository implements ConversationMessageRepository {
    private static final TypeReference<List<MessageContentBlock>> BLOCKS_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建消息 JDBC 仓储。
     *
     * @param jdbcClient 执行具名参数 SQL 的客户端
     * @param objectMapper 序列化受控内容块快照的 JSON 映射器
     * @param transactionTemplate 包裹行锁、序号分配和会话时间更新的事务模板
     */
    public JdbcConversationMessageRepository(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    /**
     * 在一次短事务内追加消息、分配序号并推进会话活动时间。
     *
     * <p>锁查询带有租户条件，因此不能利用其他租户的会话行获得序号或写入消息；任一步失败时事务回滚，
     * 不会留下已推进时间但未写入消息的半成品状态。</p>
     */
    public ConversationMessage append(UUID tenantId, ConversationMessageDraft draft) {
        Objects.requireNonNull(draft, "draft 不能为空");
        if (!Objects.requireNonNull(tenantId, "tenantId 不能为空").equals(draft.tenantId())) {
            throw new IllegalArgumentException("tenantId 与消息不匹配");
        }
        return transactionTemplate.execute(status -> {
            // 锁定会话行可让同一会话的序号分配串行化，同时不同会话仍能并发追加。
            String conversationId = jdbcClient.sql("""
                            SELECT id FROM conversations
                            WHERE tenant_id = :tenantId AND id = :conversationId
                            FOR UPDATE
                            """)
                    .param("tenantId", tenantId.toString())
                    .param("conversationId", draft.conversationId().toString())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new NoSuchElementException("会话不存在"));
            long sequence = jdbcClient.sql("""
                            SELECT COALESCE(MAX(sequence_no), 0) + 1
                            FROM messages
                            WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                            """)
                    .param("tenantId", tenantId.toString())
                    .param("conversationId", conversationId)
                    .query(Long.class)
                    .single();
            ConversationMessage message = new ConversationMessage(
                    draft.id(), draft.tenantId(), draft.conversationId(), sequence, draft.role(),
                    draft.senderName(), draft.contentBlocks(), draft.runId(), draft.createdAt());
            jdbcClient.sql("""
                            INSERT INTO messages (
                                id, tenant_id, conversation_id, role, content, created_at,
                                sequence_no, sender_name, content_blocks_json, run_id
                            ) VALUES (
                                :id, :tenantId, :conversationId, :role, :content, :createdAt,
                                :sequence, :senderName, :contentBlocks, :runId
                            )
                            """)
                    .param("id", message.id().toString())
                    .param("tenantId", tenantId.toString())
                    .param("conversationId", message.conversationId().toString())
                    .param("role", message.role().name())
                    .param("content", message.textContent())
                    .param("createdAt", Timestamp.from(message.createdAt()))
                    .param("sequence", message.sequence())
                    .param("senderName", message.senderName())
                    .param("contentBlocks", writeBlocks(message.contentBlocks()))
                    .param("runId", message.runId() == null ? null : message.runId().toString())
                    .update();
            jdbcClient.sql("""
                            UPDATE conversations SET updated_at = :updatedAt
                            WHERE tenant_id = :tenantId AND id = :conversationId
                            """)
                    .param("updatedAt", Timestamp.from(message.createdAt()))
                    .param("tenantId", tenantId.toString())
                    .param("conversationId", conversationId)
                    .update();
            return message;
        });
    }

    @Override
    /**
     * 以正序读取排他序号游标后的消息，供接口稳定分页。
     *
     * <p>查询条件显式包含 tenant，且只以 {@code sequence_no} 排序，不能用展示时间替代会话回放顺序。
     * 读取到损坏的内容块 JSON 会在映射时失败，避免把不完整历史静默交给模型。</p>
     */
    public List<ConversationMessage> list(
            UUID tenantId, UUID conversationId, MessagePageRequest pageRequest) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, conversation_id, role, sequence_no, sender_name,
                               content_blocks_json, run_id, created_at
                        FROM messages
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                          AND sequence_no > :afterSequence
                        ORDER BY sequence_no ASC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString())
                .param("conversationId", conversationId.toString())
                .param("afterSequence", pageRequest.afterSequence())
                .param("limit", pageRequest.limit())
                .query(this::mapMessage)
                .list();
    }

    @Override
    /**
     * 先按倒序限制窗口以减少数据库读取，再在内存中恢复正序供提示词编排使用。
     *
     * <p>该查询刻意不复用 API 分页路径：模型只需要最近的完整消息，而正序返回使调用方无需再反转，
     * 从而避免历史窗口与 {@code sequence} 的关联关系被破坏。</p>
     */
    public List<ConversationMessage> listRecent(UUID tenantId, UUID conversationId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        }
        List<ConversationMessage> descending = jdbcClient.sql("""
                        SELECT id, tenant_id, conversation_id, role, sequence_no, sender_name,
                               content_blocks_json, run_id, created_at
                        FROM messages
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        ORDER BY sequence_no DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString())
                .param("conversationId", conversationId.toString())
                .param("limit", limit)
                .query(this::mapMessage)
                .list();
        return descending.reversed();
    }

    /**
     * 将 JSON 内容块恢复为领域快照；解析失败表示已持久化数据不符合约定，不能静默降级为空消息。
     */
    private ConversationMessage mapMessage(ResultSet resultSet, int rowNum) throws SQLException {
        String runId = resultSet.getString("run_id");
        return new ConversationMessage(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("conversation_id")),
                resultSet.getLong("sequence_no"),
                MessageRole.valueOf(resultSet.getString("role")),
                resultSet.getString("sender_name"),
                readBlocks(resultSet.getString("content_blocks_json")),
                runId == null ? null : UUID.fromString(runId),
                resultSet.getTimestamp("created_at").toInstant());
    }

    /** 将受控内容块写为 JSON；序列化失败属于调用方输入或映射配置错误，故转换为参数异常。 */
    private String writeBlocks(List<MessageContentBlock> blocks) {
        try {
            return objectMapper.writeValueAsString(blocks);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("消息内容块无法序列化", exception);
        }
    }

    /** 将数据库 JSON 还原为内容块；失败时暴露数据完整性问题而非伪造缺失历史。 */
    private List<MessageContentBlock> readBlocks(String json) {
        try {
            return objectMapper.readValue(json, BLOCKS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的消息内容块无法解析", exception);
        }
    }
}
