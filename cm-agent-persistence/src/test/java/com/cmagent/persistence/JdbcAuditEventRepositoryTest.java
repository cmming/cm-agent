package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
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
class JdbcAuditEventRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcAuditEventRepository repository;

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

        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcAuditEventRepository(jdbcClient);
    }

    @Test
    void appendAndListByTenant() {
        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        repository.append(new AuditEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                tenantA,
                "principal-a",
                "agent.run",
                "agent",
                "agent-a",
                "SUCCESS",
                "运行成功",
                Instant.parse("2026-06-18T01:00:00Z")
        ));
        repository.append(new AuditEvent(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                tenantB,
                "principal-b",
                "agent.run",
                "agent",
                "agent-b",
                "SUCCESS",
                "运行成功",
                Instant.parse("2026-06-18T02:00:00Z")
        ));

        List<AuditEvent> events = repository.listByTenant(tenantA, 20);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().tenantId()).isEqualTo(tenantA);
        assertThat(events.getFirst().resourceId()).isEqualTo("agent-a");
    }

    private static void seedTenants(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-18T00:00:00Z");

        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, :enabled, :createdAt)
                        """)
                .param("id", "00000000-0000-0000-0000-000000000001")
                .param("code", "tenant-a")
                .param("name", "租户A")
                .param("enabled", true)
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, :enabled, :createdAt)
                        """)
                .param("id", "00000000-0000-0000-0000-000000000002")
                .param("code", "tenant-b")
                .param("name", "租户B")
                .param("enabled", true)
                .param("createdAt", now)
                .update();
    }
}
