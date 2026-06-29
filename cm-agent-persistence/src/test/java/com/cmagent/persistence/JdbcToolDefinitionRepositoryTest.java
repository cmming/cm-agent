package com.cmagent.persistence;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcToolDefinitionRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolDefinitionRepository repository;

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

        seedTenants(dataSource);
        repository = new JdbcToolDefinitionRepository(JdbcClient.create(dataSource));
    }

    @Test
    void saveFindAndListByTenant() {
        ToolDefinition toolA = tool(UUID.fromString("20000000-0000-0000-0000-000000000001"), TENANT_A, "echo");
        ToolDefinition toolB = tool(UUID.fromString("20000000-0000-0000-0000-000000000002"), TENANT_B, "calc");

        repository.save(toolA);
        repository.save(toolB);

        assertThat(repository.findByTenantAndId(TENANT_A, toolA.id())).contains(toolA);
        assertThat(repository.findByTenantAndId(TENANT_B, toolA.id())).isEmpty();
        assertThat(repository.listByTenant(TENANT_A))
                .extracting(ToolDefinition::id)
                .containsExactly(toolA.id());
    }

    private static ToolDefinition tool(UUID id, UUID tenantId, String name) {
        return new ToolDefinition(
                id,
                tenantId,
                name,
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        );
    }

    private static void seedTenants(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
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
}
