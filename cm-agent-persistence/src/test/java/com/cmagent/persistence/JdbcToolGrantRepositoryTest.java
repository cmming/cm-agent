package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcToolGrantRepositoryTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID AGENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolGrantRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        repository = new JdbcToolGrantRepository(JdbcClient.create(dataSource));
    }

    @Test
    void saveIsIdempotentAndListsByTenantAndAgent() {
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);

        repository.save(grant);
        repository.save(grant);

        assertThat(repository.listByTenant(TENANT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(grant);
    }

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
        new JdbcAgentDefinitionRepository(JdbcClient.create(dataSource), new ObjectMapper()).save(new AgentDefinition(
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
    }
}
