package com.cmagent.persistence;

import com.cmagent.core.domain.McpToolPublication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcMcpToolPublicationRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A_DISABLED = UUID.fromString("20000000-0000-0000-0000-000000000003");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcMcpToolPublicationRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcHttpToolConfigRepositoryTest.seedData(dataSource);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcHttpToolConfigRepositoryTest.insertTool(jdbc, TOOL_A_DISABLED, TENANT_A, "http-a-disabled",
                new java.sql.Timestamp(java.time.Instant.parse("2026-07-21T00:00:00Z").toEpochMilli()));
        repository = new JdbcMcpToolPublicationRepository(JdbcClient.create(dataSource), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void listsOnlyEnabledPublicationsForTheCurrentTenant() {
        McpToolPublication enabledA = new McpToolPublication(TENANT_A, TOOL_A, true, "admin-a");
        McpToolPublication disabledA = new McpToolPublication(TENANT_A, TOOL_A_DISABLED, false, "admin-a");
        McpToolPublication enabledB = new McpToolPublication(TENANT_B, TOOL_B, true, "admin-b");
        repository.save(enabledA);
        repository.save(disabledA);
        repository.save(enabledB);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(enabledA);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_A)).isEmpty();
        assertThat(repository.listEnabledByTenant(TENANT_A))
                .extracting(McpToolPublication::toolId).containsExactly(TOOL_A);
    }

    @Test
    void updatesAndDeletesOnlyTheTargetTenantPublication() {
        McpToolPublication enabled = new McpToolPublication(TENANT_A, TOOL_A, true, "admin-a");
        McpToolPublication disabled = new McpToolPublication(TENANT_A, TOOL_A, false, "admin-a");
        McpToolPublication otherTenant = new McpToolPublication(TENANT_B, TOOL_B, true, "admin-b");
        repository.save(enabled);
        repository.save(otherTenant);

        repository.save(disabled);
        repository.delete(TENANT_B, TOOL_A);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(disabled);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(otherTenant);

        repository.delete(TENANT_A, TOOL_A);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).isEmpty();
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(otherTenant);
    }
}
