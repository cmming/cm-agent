package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditPageRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "principal-a-1",
                "agent.run",
                "agent",
                "agent-a-1",
                "SUCCESS",
                "运行成功-1",
                Instant.parse("2026-06-18T01:00:00Z")
        ));
        repository.append(new AuditEvent(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                tenantA,
                "principal-a-2",
                "agent.run",
                "agent",
                "agent-a-2",
                "SUCCESS",
                "运行成功-2",
                Instant.parse("2026-06-18T02:00:00Z")
        ));
        repository.append(new AuditEvent(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                tenantA,
                "principal-a-3",
                "agent.run",
                "agent",
                "agent-a-3",
                "SUCCESS",
                "运行成功-3",
                Instant.parse("2026-06-18T03:00:00Z")
        ));
        repository.append(new AuditEvent(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                tenantB,
                "principal-b",
                "agent.run",
                "agent",
                "agent-b",
                "SUCCESS",
                "运行成功",
                Instant.parse("2026-06-18T04:00:00Z")
        ));

        List<AuditEvent> events = repository.listByTenant(tenantA, 2);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(AuditEvent::tenantId).containsOnly(tenantA);
        assertThat(events).extracting(AuditEvent::resourceId)
                .containsExactly("agent-a-3", "agent-a-2");
        assertThat(events.getFirst().principalId()).isEqualTo("principal-a-3");
        assertThat(events.getFirst().eventType()).isEqualTo("agent.run");
        assertThat(events.getFirst().message()).isEqualTo("运行成功-3");
        assertThat(events.getFirst().createdAt()).isEqualTo(Instant.parse("2026-06-18T03:00:00Z"));
        assertThat(events.get(1).principalId()).isEqualTo("principal-a-2");
        assertThat(events.get(1).message()).isEqualTo("运行成功-2");
    }

    @Test
    void listByTenantRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> repository.listByTenant(UUID.fromString("00000000-0000-0000-0000-000000000001"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit 必须大于 0");
    }

    @Test
    void advertisesCursorPaginationSupport() {
        assertThat(repository.supportsCursorPagination()).isTrue();
    }

    @Test
    void listByTenantPageUsesCreatedAtAndIdKeysetWithinTenant() {
        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant sameTime = Instant.parse("2026-06-18T05:00:00Z");
        AuditEvent older = auditEvent("11111111-1111-1111-1111-111111111111", tenantA, sameTime.minusSeconds(1));
        AuditEvent lowerId = auditEvent("22222222-2222-2222-2222-222222222222", tenantA, sameTime);
        AuditEvent higherId = auditEvent("33333333-3333-3333-3333-333333333333", tenantA, sameTime);
        AuditEvent otherTenant = auditEvent("44444444-4444-4444-4444-444444444444", tenantB, sameTime.plusSeconds(1));

        repository.append(older);
        repository.append(lowerId);
        repository.append(higherId);
        repository.append(otherTenant);

        List<AuditEvent> firstPage = repository.listByTenant(tenantA, new AuditPageRequest(2, null, null));
        assertThat(firstPage).containsExactly(higherId, lowerId);

        List<AuditEvent> secondPage = repository.listByTenant(tenantA, new AuditPageRequest(
                2, lowerId.createdAt(), lowerId.id()));
        assertThat(secondPage).containsExactly(older);
        assertThat(secondPage).extracting(AuditEvent::tenantId).containsOnly(tenantA);
    }

    private static AuditEvent auditEvent(String id, UUID tenantId, Instant createdAt) {
        return new AuditEvent(
                UUID.fromString(id), tenantId, "principal", "TEST", "RESOURCE", id,
                "SUCCEEDED", "message", createdAt
        );
    }

    private static void seedTenants(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-06-18T00:00:00Z"));

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
