package com.cmagent.persistence;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import org.flywaydb.core.Flyway;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcHttpToolConfigRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcHttpToolConfigRepository repository;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        repository = repository(dataSource);
    }

    @Test
    void savesNestedMappingsDefaultsAndSecretReferencesWithinTenant() {
        HttpToolConfig configA = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig configB = config(TENANT_B, TOOL_B, "https://api-b.invalid/v1/{customerId}", Duration.ofSeconds(5));

        repository.save(configA);
        repository.save(configB);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(configA);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(configB);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_A)).isEmpty();
        HttpToolConfig stored = repository.findByTenantAndToolId(TENANT_A, TOOL_A).orElseThrow();
        assertThat(stored.parameterMappings()).contains(new HttpParameterMapping(
                "/request/options/limit", HttpParameterLocation.QUERY, "limit", "", false, "20"));
        assertThat(stored.secretHeaders()).containsExactly(Map.entry("X-Api-Key", "secret/http/tenant-a"));
    }

    @Test
    void updatesAndDeletesOnlyTheTargetTenantConfiguration() {
        HttpToolConfig original = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig updated = config(TENANT_A, TOOL_A, "https://api-a.invalid/v2/{customerId}", Duration.ofSeconds(7));
        HttpToolConfig otherTenant = config(TENANT_B, TOOL_B, "https://api-b.invalid/v1/{customerId}", Duration.ofSeconds(5));
        repository.save(original);
        repository.save(otherTenant);

        repository.save(updated);
        repository.delete(TENANT_B, TOOL_A);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(updated);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(otherTenant);

        repository.delete(TENANT_A, TOOL_A);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).isEmpty();
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(otherTenant);
    }

    @Test
    void bulkFindUsesTenantScope() {
        HttpToolConfig configA = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig configB = config(TENANT_B, TOOL_B, "https://api-b.invalid/v1/{customerId}", Duration.ofSeconds(5));
        repository.save(configA);
        repository.save(configB);

        assertThat(repository.findByTenantAndToolIds(TENANT_A, List.of(TOOL_A, TOOL_B)))
                .containsExactly(Map.entry(TOOL_A, configA));
    }

    @Test
    void rejectsSecretHeaderValuesThatAreNotReferencesBeforePersistence() {
        assertThatThrownBy(() -> repository.save(new HttpToolConfig(
                TENANT_A, TOOL_A, HttpToolMethod.POST, "https://api-a.invalid", "{}", List.of(),
                Map.of("Authorization", "实际密钥值"), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretHeaders 必须使用 secret/ 开头的引用");
    }

    @Test
    void concurrentFirstSavesAreIdempotent() throws Exception {
        HttpToolConfig first = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig second = config(TENANT_A, TOOL_A, "https://api-a.invalid/v2/{customerId}", Duration.ofSeconds(4));
        JdbcHttpToolConfigRepository firstRepository = repository(dataSource);
        JdbcHttpToolConfigRepository secondRepository = repository(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstSave = executor.submit(() -> saveAfterStart(firstRepository, first, ready, start));
            var secondSave = executor.submit(() -> saveAfterStart(secondRepository, second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            firstSave.get(20, TimeUnit.SECONDS);
            secondSave.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).get().isIn(first, second);
    }

    private static void saveAfterStart(
            JdbcHttpToolConfigRepository repository,
            HttpToolConfig config,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        awaitStart(start);
        repository.save(config);
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发保存测试被中断", exception);
        }
    }

    private static JdbcHttpToolConfigRepository repository(DataSource dataSource) {
        return new JdbcHttpToolConfigRepository(
                JdbcClient.create(dataSource),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    static HttpToolConfig config(UUID tenantId, UUID toolId, String urlTemplate, Duration timeout) {
        return new HttpToolConfig(
                tenantId,
                toolId,
                HttpToolMethod.POST,
                urlTemplate,
                "{\"type\":\"object\",\"properties\":{\"request\":{\"type\":\"object\"}}}",
                List.of(
                        new HttpParameterMapping("/request/customer/id", HttpParameterLocation.PATH,
                                "customerId", "", true, ""),
                        new HttpParameterMapping("/request/options/limit", HttpParameterLocation.QUERY,
                                "limit", "", false, "20"),
                        new HttpParameterMapping("/request/payload", HttpParameterLocation.BODY,
                                "", "/payload", true, "")
                ),
                Map.of("X-Api-Key", "secret/http/tenant-a"),
                timeout
        );
    }

    static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-21T00:00:00Z"));
        insertTenant(jdbc, TENANT_A, "tenant-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-b", now);
        insertTool(jdbc, TOOL_A, TENANT_A, "http-a", now);
        insertTool(jdbc, TOOL_B, TENANT_B, "http-b", now);
    }

    static void insertTenant(JdbcClient jdbc, UUID tenantId, String code, Timestamp now) {
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", tenantId.toString()).param("code", code).param("name", code).param("createdAt", now).update();
    }

    static void insertTool(JdbcClient jdbc, UUID toolId, UUID tenantId, String name, Timestamp now) {
        jdbc.sql("""
                        INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema, risk_level,
                            enabled, endpoint, created_by, updated_by, created_at, updated_at)
                        VALUES (:id, :tenantId, :name, 'HTTP 工具', 'HTTP', '{}', 'LOW', true, '', 'tester', 'tester',
                            :createdAt, :updatedAt)
                        """)
                .param("id", toolId.toString()).param("tenantId", tenantId.toString()).param("name", name)
                .param("createdAt", now).param("updatedAt", now).update();
    }
}
