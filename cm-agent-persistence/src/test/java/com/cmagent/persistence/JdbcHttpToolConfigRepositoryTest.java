package com.cmagent.persistence;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterDataType;
import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
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
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        CmAgentFlyway.configure(dataSource)
                .cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedData(dataSource);
        repository = repository(dataSource);
    }

    @Test
    /**
     * 验证持久化能够保存 {@code NestedMappingsDefaultsAndSecretReferencesWithinTenant}。
     */
    void 保存参数定义默认值和Secret引用时保持租户隔离() {
        HttpToolConfig configA = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig configB = config(TENANT_B, TOOL_B, "https://api-b.invalid/v1/{customerId}", Duration.ofSeconds(5));

        repository.save(configA);
        repository.save(configB);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(configA);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_B)).contains(configB);
        assertThat(repository.findByTenantAndToolId(TENANT_B, TOOL_A)).isEmpty();
        HttpToolConfig stored = repository.findByTenantAndToolId(TENANT_A, TOOL_A).orElseThrow();
        assertThat(stored.parameters()).anyMatch(parameter ->
                parameter.name().equals("limit") && parameter.defaultValueJson().equals("20"));
        assertThat(stored.secretHeaders()).containsExactly(Map.entry("X-Api-Key", "secret/http/tenant-a"));
    }

    @Test
    void 保存并读取新版扁平参数定义() {
        List<HttpParameterDefinition> parameters = List.of(
                definition("orderId", "", "orderId", HttpParameterDataType.STRING,
                        HttpParameterLocation.PATH, true),
                definition("payload", "", "payload", HttpParameterDataType.ARRAY,
                        HttpParameterLocation.BODY_ROOT, true),
                definition("payloadItem", "payload", "", HttpParameterDataType.STRING, null, false)
        );
        HttpToolConfig config = new HttpToolConfig(
                TENANT_A,
                TOOL_A,
                HttpToolMethod.POST,
                "https://api-a.invalid/v1/{orderId}",
                parameters,
                Map.of(),
                Duration.ofSeconds(3)
        );

        repository.save(config);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(config);
        assertThat(JdbcClient.create(dataSource).sql("""
                        SELECT parameter_definitions FROM tool_http_configs
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("tenantId", TENANT_A.toString())
                .param("toolId", TOOL_A.toString())
                .query(String.class)
                .single()).contains("BODY_ROOT", "payloadItem");
    }

    @Test
    void 保存并读取空参数定义() {
        HttpToolConfig config = new HttpToolConfig(
                TENANT_A,
                TOOL_A,
                HttpToolMethod.GET,
                "https://api-a.invalid/v1/tools",
                List.of(),
                Map.of(),
                Duration.ofSeconds(3)
        );

        repository.save(config);

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(config);
        assertThat(JdbcClient.create(dataSource).sql("""
                        SELECT parameter_definitions FROM tool_http_configs
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("tenantId", TENANT_A.toString())
                .param("toolId", TOOL_A.toString())
                .query(String.class)
                .single()).isEqualTo("[]");
    }

    @Test
    /**
     * 验证更新流程能够处理 {@code AndDeletesOnlyTheTargetTenantConfiguration}。
     */
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
    /**
     * 验证或支持 {@code bulkFindUsesTenantScope} 所描述的测试场景。
     */
    void bulkFindUsesTenantScope() {
        HttpToolConfig configA = config(TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig configB = config(TENANT_B, TOOL_B, "https://api-b.invalid/v1/{customerId}", Duration.ofSeconds(5));
        repository.save(configA);
        repository.save(configB);

        assertThat(repository.findByTenantAndToolIds(TENANT_A, List.of(TOOL_A, TOOL_B)))
                .containsExactly(Map.entry(TOOL_A, configA));
    }

    @Test
    /**
     * 验证 {@code SecretHeaderValuesThatAreNotReferencesBeforePersistence} 异常场景会被正确拒绝。
     */
    void rejectsSecretHeaderValuesThatAreNotReferencesBeforePersistence() {
        assertThatThrownBy(() -> repository.save(new HttpToolConfig(
                TENANT_A, TOOL_A, HttpToolMethod.POST, "https://api-a.invalid",
                List.of(definition("payload", "", "payload", HttpParameterDataType.STRING,
                        HttpParameterLocation.BODY, true)),
                Map.of("Authorization", "实际密钥值"), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretHeaders 必须使用 secret/ 开头的引用");
    }

    @Test
    /**
     * 验证或支持 {@code concurrentFirstSavesAreIdempotent} 所描述的测试场景。
     */
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

    /**
     * 验证或支持 {@code saveAfterStart} 所描述的测试场景。
     *
     * @param repository 测试仓储
     * @param config 测试配置
     * @param ready 测试辅助方法使用的 ready 参数
     * @param start 测试辅助方法使用的 start 参数
     */
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

    /**
     * 验证或支持 {@code awaitStart} 所描述的测试场景。
     *
     * @param start 测试辅助方法使用的 start 参数
     */
    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发保存测试被中断", exception);
        }
    }

    /**
     * 验证或支持 {@code repository} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    private static JdbcHttpToolConfigRepository repository(DataSource dataSource) {
        return new JdbcHttpToolConfigRepository(
                JdbcClient.create(dataSource),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    /**
     * 构造测试配置。
     *
     * @param tenantId 测试租户标识
     * @param toolId 测试工具标识
     * @param urlTemplate 测试辅助方法使用的 urlTemplate 参数
     * @param timeout 测试超时
     */
    static HttpToolConfig config(UUID tenantId, UUID toolId, String urlTemplate, Duration timeout) {
        return new HttpToolConfig(
                tenantId,
                toolId,
                HttpToolMethod.POST,
                urlTemplate,
                List.of(
                        definition("customerId", "", "customerId", HttpParameterDataType.STRING,
                                HttpParameterLocation.PATH, true),
                        new HttpParameterDefinition(
                                "limit", "", "limit", HttpParameterDataType.INTEGER,
                                HttpParameterLocation.QUERY, "分页数量", false, "20", "", List.of(),
                                null, null, null, null, null, null, false
                        ),
                        definition("payload", "", "payload", HttpParameterDataType.STRING,
                                HttpParameterLocation.BODY, true)
                ),
                Map.of("X-Api-Key", "secret/http/tenant-a"),
                timeout
        );
    }

    private static HttpParameterDefinition definition(
            String id,
            String parentId,
            String name,
            HttpParameterDataType dataType,
            HttpParameterLocation location,
            boolean required
    ) {
        return new HttpParameterDefinition(
                id, parentId, name, dataType, location, id, required, "", "", List.of(),
                null, null, null, null, null, null, false
        );
    }

    /**
     * 验证或支持 {@code seedData} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-21T00:00:00Z"));
        insertTenant(jdbc, TENANT_A, "tenant-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-b", now);
        insertTool(jdbc, TOOL_A, TENANT_A, "http-a", now);
        insertTool(jdbc, TOOL_B, TENANT_B, "http-b", now);
    }

    /**
     * 验证或支持 {@code insertTenant} 所描述的测试场景。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param tenantId 测试租户标识
     * @param code 测试辅助方法使用的 code 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    static void insertTenant(JdbcClient jdbc, UUID tenantId, String code, Timestamp now) {
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", tenantId.toString()).param("code", code).param("name", code).param("createdAt", now).update();
    }

    /**
     * 验证或支持 {@code insertTool} 所描述的测试场景。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param toolId 测试工具标识
     * @param tenantId 测试租户标识
     * @param name 测试对象名称
     * @param now 测试辅助方法使用的 now 参数
     */
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
