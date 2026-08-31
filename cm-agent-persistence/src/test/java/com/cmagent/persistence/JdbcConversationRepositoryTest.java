package com.cmagent.persistence;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcConversationRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcConversationRepository conversationRepository;
    private JdbcConversationMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedAgents(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        conversationRepository = new JdbcConversationRepository(jdbcClient);
        messageRepository = new JdbcConversationMessageRepository(
                jdbcClient,
                new ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    /** 验证类型化消息块能够按稳定序号写入、分页读取，并同步推进会话更新时间。 */
    void messagesRoundTripWithStableSequenceAndTypedBlocks() {
        Instant createdAt = Instant.parse("2026-08-31T01:00:00Z");
        UUID conversationId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        conversationRepository.save(TENANT_A, new Conversation(
                conversationId, TENANT_A, AGENT_A, "测试会话", "tester", createdAt, createdAt));

        ConversationMessage user = messageRepository.append(TENANT_A, new ConversationMessageDraft(
                UUID.fromString("30000000-0000-0000-0000-000000000001"), TENANT_A, conversationId,
                MessageRole.USER, "测试用户", List.of(MessageContentBlock.text("你好")), null,
                createdAt.plusSeconds(1)));
        ConversationMessage assistant = messageRepository.append(TENANT_A, new ConversationMessageDraft(
                UUID.fromString("30000000-0000-0000-0000-000000000002"), TENANT_A, conversationId,
                MessageRole.ASSISTANT, "测试 Agent", List.of(
                        MessageContentBlock.toolUse("call-1", "echo", "已脱敏参数"),
                        MessageContentBlock.toolResult("call-1", RunStatus.SUCCEEDED, "已脱敏结果"),
                        MessageContentBlock.text("完成")), null, createdAt.plusSeconds(2)));

        assertThat(user.sequence()).isEqualTo(1);
        assertThat(assistant.sequence()).isEqualTo(2);
        assertThat(messageRepository.list(TENANT_A, conversationId, new MessagePageRequest(20, 0)))
                .containsExactly(user, assistant);
        assertThat(messageRepository.list(TENANT_A, conversationId, new MessagePageRequest(20, 1)))
                .containsExactly(assistant);
        assertThat(messageRepository.listRecent(TENANT_A, conversationId, 1)).containsExactly(assistant);
        assertThat(conversationRepository.findByTenantAndAgentAndId(TENANT_A, AGENT_A, conversationId))
                .get().extracting(Conversation::updatedAt).isEqualTo(createdAt.plusSeconds(2));
    }

    @Test
    /** 验证所有会话与消息查询都必须同时命中可信 tenant 和 Agent 边界。 */
    void anotherTenantCannotDiscoverConversationOrMessages() {
        Instant now = Instant.parse("2026-08-31T02:00:00Z");
        UUID conversationId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        conversationRepository.save(TENANT_A, new Conversation(
                conversationId, TENANT_A, AGENT_A, "隔离会话", "tester", now, now));
        messageRepository.append(TENANT_A, new ConversationMessageDraft(
                UUID.randomUUID(), TENANT_A, conversationId, MessageRole.USER, "tester",
                List.of(MessageContentBlock.text("tenant-a")), null, now.plusSeconds(1)));

        assertThat(conversationRepository.findByTenantAndAgentAndId(TENANT_B, AGENT_B, conversationId)).isEmpty();
        assertThat(conversationRepository.listByTenantAndAgent(
                TENANT_B, AGENT_B, new ConversationPageRequest(20, null, null))).isEmpty();
        assertThat(messageRepository.list(TENANT_B, conversationId, new MessagePageRequest(20, 0))).isEmpty();
    }

    private static void seedAgents(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-31T00:00:00Z"));
        seedTenantAndAgent(jdbcClient, TENANT_A, AGENT_A,
                UUID.fromString("40000000-0000-0000-0000-000000000001"), "tenant-a", now);
        seedTenantAndAgent(jdbcClient, TENANT_B, AGENT_B,
                UUID.fromString("40000000-0000-0000-0000-000000000002"), "tenant-b", now);
    }

    private static void seedTenantAndAgent(
            JdbcClient jdbcClient,
            UUID tenantId,
            UUID agentId,
            UUID modelId,
            String tenantCode,
            Timestamp now
    ) {
        jdbcClient.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", tenantId.toString())
                .param("code", tenantCode)
                .param("name", tenantCode)
                .param("createdAt", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', 'test', 'https://example.invalid',
                            'test-model', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", modelId.toString())
                .param("tenantId", tenantId.toString())
                .param("createdAt", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO agent_definitions (
                            id, tenant_id, name, description, system_prompt, model_provider_id, model_name,
                            temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by,
                            created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, 'test-agent', '', 'test', :modelId, 'test-model',
                            0.2, 6, true, '[]', 'tester', 'tester', :createdAt, :updatedAt
                        )
                        """)
                .param("id", agentId.toString())
                .param("tenantId", tenantId.toString())
                .param("modelId", modelId.toString())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }
}
