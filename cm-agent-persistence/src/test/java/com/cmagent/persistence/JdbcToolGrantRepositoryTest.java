package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
class JdbcToolGrantRepositoryTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID AGENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolGrantRepository repository;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedData(dataSource);
        repository = new JdbcToolGrantRepository(JdbcClient.create(dataSource));
    }

    @Test
    /**
     * 验证 {@code saveIsIdempotentAndListsByTenantAndAgent} 所描述的业务行为。
     */
    void saveIsIdempotentAndListsByTenantAndAgent() {
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);

        repository.save(grant);
        repository.save(grant);

        assertThat(repository.listByTenant(TENANT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(grant);
    }

    @Test
    /**
     * 验证 {@code deleteRemovesOnlyMatchingAgentToolGrant} 所描述的业务行为。
     */
    void deleteRemovesOnlyMatchingAgentToolGrant() {
        UUID otherAgentId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        ToolGrant selected = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        ToolGrant otherAgent = new ToolGrant(TENANT_ID, TOOL_ID, otherAgentId, null, true);
        repository.save(selected);
        repository.save(otherAgent);

        repository.delete(TENANT_ID, AGENT_ID, TOOL_ID);

        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).isEmpty();
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, otherAgentId, TOOL_ID)).containsExactly(otherAgent);
    }

    @Test
    /**
     * 验证 {@code deleteByTenantAndToolIdRemovesAllGrantsForTool} 所描述的业务行为。
     */
    void deleteByTenantAndToolIdRemovesAllGrantsForTool() {
        UUID otherAgentId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID otherToolId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        ToolGrant first = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        ToolGrant second = new ToolGrant(TENANT_ID, TOOL_ID, otherAgentId, null, true);
        ToolGrant retained = new ToolGrant(TENANT_ID, otherToolId, AGENT_ID, null, true);
        repository.save(first);
        repository.save(second);
        repository.save(retained);

        repository.deleteByTenantAndToolId(TENANT_ID, TOOL_ID);

        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).isEmpty();
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, otherAgentId, TOOL_ID)).isEmpty();
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, otherToolId)).containsExactly(retained);
    }

    @Test
    /**
     * 验证 {@code deleteWithWrongTenantLeavesGrantUnchanged} 所描述的业务行为。
     */
    void deleteWithWrongTenantLeavesGrantUnchanged() {
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        repository.save(grant);

        repository.delete(OTHER_TENANT_ID, AGENT_ID, TOOL_ID);

        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(grant);
    }

    @Test
    /**
     * 验证 {@code deleteByTenantAndToolIdWithWrongTenantLeavesGrantsUnchanged} 所描述的业务行为。
     */
    void deleteByTenantAndToolIdWithWrongTenantLeavesGrantsUnchanged() {
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        repository.save(grant);

        repository.deleteByTenantAndToolId(OTHER_TENANT_ID, TOOL_ID);

        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(grant);
    }

    /**
     * 验证 {@code seedData} 所描述的业务行为。
     *
     * @param dataSource 测试数据源
     */
    private static void seedData(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-06-26T00:00:00Z"));
        jdbcClient.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, 'default', '默认租户', true, :createdAt)")
                .param("id", TENANT_ID.toString())
                .param("createdAt", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '默认模型', 'https://example.invalid',
                            'qwen-max', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", MODEL_PROVIDER_ID.toString())
                .param("tenantId", TENANT_ID.toString())
                .param("createdAt", now)
                .update();
        newAgentRepository(dataSource).save(new AgentDefinition(
                AGENT_ID,
                TENANT_ID,
                "企业助手",
                "",
                "你是企业助手",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                List.of(),
                "tester",
                "tester"
        ));
        newAgentRepository(dataSource).save(new AgentDefinition(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                TENANT_ID,
                "其他助手",
                "",
                "你是企业助手",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                List.of(),
                "tester",
                "tester"
        ));
        newAgentRepository(dataSource).save(new AgentDefinition(
                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                TENANT_ID,
                "第三助手",
                "",
                "你是企业助手",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                List.of(),
                "tester",
                "tester"
        ));
        new JdbcToolDefinitionRepository(JdbcClient.create(dataSource)).save(new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "echo",
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        ));
        new JdbcToolDefinitionRepository(JdbcClient.create(dataSource)).save(new ToolDefinition(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                TENANT_ID,
                "calc",
                "计算输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        ));
    }

    /**
     * 验证 {@code newAgentRepository} 所描述的业务行为。
     *
     * @param dataSource 测试数据源
     */
    private static JdbcAgentDefinitionRepository newAgentRepository(DataSource dataSource) {
        return new JdbcAgentDefinitionRepository(
                JdbcClient.create(dataSource),
                new ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }
}
