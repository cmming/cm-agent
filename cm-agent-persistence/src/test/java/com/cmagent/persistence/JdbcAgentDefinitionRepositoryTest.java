package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        seedTenantAndModelConfigs(dataSource);
        repository = new JdbcAgentDefinitionRepository(JdbcClient.create(dataSource), new ObjectMapper());
    }

    @Test
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
    void addToolToAgentPersistsUniqueToolId() {
        UUID agentId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID newToolId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        repository.save(agent(agentId, TENANT_A, MODEL_PROVIDER_A, "工具助手", List.of(TOOL_ID)));

        repository.addToolToAgent(TENANT_A, agentId, newToolId);
        repository.addToolToAgent(TENANT_A, agentId, newToolId);

        AgentDefinition saved = repository.findByTenantAndId(TENANT_A, agentId).orElseThrow();
        assertThat(saved.toolIds()).containsExactly(TOOL_ID, newToolId);
    }

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

    private static void seedTenantAndModelConfigs(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_A, TENANT_A, now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_B, TENANT_B, now);
    }

    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, String name, Instant now) {
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

    private static void insertModelConfig(JdbcClient jdbcClient, UUID modelProviderId, UUID tenantId, Instant now) {
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
