package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcAgentDefinitionRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_PROVIDER_A = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID MODEL_PROVIDER_B = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcAgentDefinitionRepository repository;

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

        CmAgentFlyway.configure(dataSource)
                .cleanDisabled(false)
                .load()
                .clean();

        CmAgentFlyway.configure(dataSource)
                .load()
                .migrate();

        seedTenantAndModelConfigs(dataSource);
        repository = new JdbcAgentDefinitionRepository(
                JdbcClient.create(dataSource),
                new ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    /**
     * 验证或支持 {@code saveFindAndListByTenant} 所描述的测试场景。
     */
    void saveFindAndListByTenant() {
        AgentDefinition agentA = agent(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                TENANT_A,
                MODEL_PROVIDER_A,
                "企业助手",
                List.of(TOOL_ID)
        );
        AgentDefinition agentB = agent(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                TENANT_B,
                MODEL_PROVIDER_B,
                "其他租户助手",
                List.of()
        );

        repository.save(agentA);
        repository.save(agentB);

        assertThat(repository.findByTenantAndId(TENANT_A, agentA.id())).contains(agentA);
        assertThat(repository.findByTenantAndId(TENANT_B, agentA.id())).isEmpty();
        assertThat(repository.listByTenant(TENANT_A))
                .extracting(AgentDefinition::id)
                .containsExactly(agentA.id());
    }

    @Test
    /**
     * 验证或支持 {@code addToolToAgentPersistsUniqueToolId} 所描述的测试场景。
     */
    void addToolToAgentPersistsUniqueToolId() {
        UUID agentId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID newToolId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        repository.save(agent(agentId, TENANT_A, MODEL_PROVIDER_A, "工具助手", List.of(TOOL_ID)));

        repository.addToolToAgent(TENANT_A, agentId, newToolId);
        repository.addToolToAgent(TENANT_A, agentId, newToolId);

        AgentDefinition saved = repository.findByTenantAndId(TENANT_A, agentId).orElseThrow();
        assertThat(saved.toolIds()).containsExactly(TOOL_ID, newToolId);
    }

    @Test
    /**
     * 验证或支持 {@code removeToolFromAgentPersistsRemainingToolIds} 所描述的测试场景。
     */
    void removeToolFromAgentPersistsRemainingToolIds() {
        UUID agentId = UUID.fromString("10000000-0000-0000-0000-000000000004");
        UUID otherToolId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        repository.save(agent(agentId, TENANT_A, MODEL_PROVIDER_A, "工具助手", List.of(TOOL_ID, otherToolId)));

        AgentDefinition updated = repository.removeToolFromAgent(TENANT_A, agentId, TOOL_ID);

        assertThat(updated.toolIds()).containsExactly(otherToolId);
        assertThat(repository.findByTenantAndId(TENANT_A, agentId).orElseThrow().toolIds())
                .containsExactly(otherToolId);
        assertThat(repository.findByTenantAndId(TENANT_B, agentId)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code removeToolFromAgentWithWrongTenantLeavesToolIdsUnchanged} 所描述的测试场景。
     */
    void removeToolFromAgentWithWrongTenantLeavesToolIdsUnchanged() {
        UUID agentId = UUID.fromString("10000000-0000-0000-0000-000000000005");
        UUID otherToolId = UUID.fromString("00000000-0000-0000-0000-000000000403");
        AgentDefinition original = agent(agentId, TENANT_A, MODEL_PROVIDER_A, "工具助手", List.of(TOOL_ID, otherToolId));
        repository.save(original);

        assertThatThrownBy(() -> repository.removeToolFromAgent(TENANT_B, agentId, TOOL_ID))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Agent 不存在");

        assertThat(repository.findByTenantAndId(TENANT_A, agentId)).contains(original);
    }

    /**
     * 构造测试 Agent 定义。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param modelProviderId 测试辅助方法使用的 modelProviderId 参数
     * @param name 测试对象名称
     * @param toolIds 测试辅助方法使用的 toolIds 参数
     */
    private static AgentDefinition agent(UUID id, UUID tenantId, UUID modelProviderId, String name, List<UUID> toolIds) {
        return new AgentDefinition(
                id,
                tenantId,
                name,
                "用于持久化测试",
                "你是企业助手",
                modelProviderId,
                "qwen-max",
                0.2d,
                6,
                true,
                toolIds,
                "tester",
                "tester"
        );
    }

    /**
     * 验证或支持 {@code seedTenantAndModelConfigs} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    private static void seedTenantAndModelConfigs(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-06-26T00:00:00Z"));
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_A, TENANT_A, now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_B, TENANT_B, now);
    }

    /**
     * 验证或支持 {@code insertTenant} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param tenantId 测试租户标识
     * @param code 测试辅助方法使用的 code 参数
     * @param name 测试对象名称
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, String name, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", name)
                .param("createdAt", now)
                .update();
    }

    /**
     * 验证或支持 {@code insertModelConfig} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param modelProviderId 测试辅助方法使用的 modelProviderId 参数
     * @param tenantId 测试租户标识
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertModelConfig(JdbcClient jdbcClient, UUID modelProviderId, UUID tenantId, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '默认模型', 'https://example.invalid',
                            'qwen-max', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", modelProviderId.toString())
                .param("tenantId", tenantId.toString())
                .param("createdAt", now)
                .update();
    }
}
