package com.cmagent.persistence;

import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcModelConfigRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_A = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcModelConfigRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        repository = new JdbcModelConfigRepository(JdbcClient.create(dataSource));
    }

    @Test
    void findsOnlyModelConfigOwnedByTenant() {
        ModelConfig own = repository.findByTenantAndId(TENANT_A, MODEL_A).orElseThrow();

        assertThat(own.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(own.baseUrl()).isEqualTo("https://model-a.invalid/v1");
        assertThat(repository.findByTenantAndId(TENANT_B, MODEL_A)).isEmpty();
    }

    private static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
        insertTenant(jdbc, TENANT_A, "tenant-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-b", now);
        jdbc.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '模型A', 'https://model-a.invalid/v1',
                            'model-a', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", MODEL_A.toString())
                .param("tenantId", TENANT_A.toString())
                .param("createdAt", now)
                .update();
    }

    private static void insertTenant(JdbcClient jdbc, UUID id, String code, Timestamp now) {
        jdbc.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", id.toString())
                .param("code", code)
                .param("name", code)
                .param("createdAt", now)
                .update();
    }
}
