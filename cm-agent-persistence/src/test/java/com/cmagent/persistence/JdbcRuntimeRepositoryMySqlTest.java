package com.cmagent.persistence;

import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcRuntimeRepositoryMySqlTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID RUN_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private JdbcRunRepository runRepository;
    private JdbcToolCallRepository toolCallRepository;
    private JdbcModelConfigRepository modelConfigRepository;
    private JdbcHttpToolConfigRepository httpToolConfigRepository;
    private JdbcMcpToolPublicationRepository mcpToolPublicationRepository;
    private DataSource dataSource;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        runRepository = new JdbcRunRepository(jdbcClient);
        modelConfigRepository = new JdbcModelConfigRepository(jdbcClient);
        toolCallRepository = new JdbcToolCallRepository(
                jdbcClient, new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        httpToolConfigRepository = new JdbcHttpToolConfigRepository(
                jdbcClient, new com.fasterxml.jackson.databind.ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        mcpToolPublicationRepository = new JdbcMcpToolPublicationRepository(
                jdbcClient, new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    /**
     * 验证 {@code mysqlFindsOnlyModelConfigOwnedByTenant} 所描述的业务行为。
     */
    void mysqlFindsOnlyModelConfigOwnedByTenant() {
        ModelConfig own = modelConfigRepository.findByTenantAndId(TENANT_A, MODEL_A).orElseThrow();

        assertThat(own.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(own.baseUrl()).isEqualTo("https://example.invalid");
        assertThat(modelConfigRepository.findByTenantAndId(TENANT_B, MODEL_A)).isEmpty();
    }

    @Test
    /**
     * 验证 {@code mysqlRunRepositoryKeepsTenantAndKeysetBoundaries} 所描述的业务行为。
     */
    void mysqlRunRepositoryKeepsTenantAndKeysetBoundaries() {
        Instant startedAt = Instant.parse("2026-07-14T03:00:00Z");
        UUID newest = UUID.fromString("30000000-0000-0000-0000-000000000010");
        UUID older = UUID.fromString("30000000-0000-0000-0000-000000000011");
        runRepository.save(TENANT_A, RunRecord.create(newest, TENANT_A, AGENT_A, "principal", "new", startedAt));
        runRepository.save(TENANT_A, RunRecord.create(older, TENANT_A, AGENT_A, "principal", "old", startedAt.minusSeconds(1)));

        assertThat(runRepository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(1, null, null)))
                .extracting(RunRecord::id).containsExactly(newest);
        assertThat(runRepository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(10, startedAt, newest)))
                .extracting(RunRecord::id).containsExactly(older, RUN_A);
        assertThat(runRepository.listByTenantAndAgent(TENANT_B, AGENT_B, new RunPageRequest(10, null, null)))
                .extracting(RunRecord::id).containsExactly(RUN_B);
    }

    @Test
    /**
     * 验证 {@code mysqlToolCallRepositoryKeepsBatchAtomicityAndTenantIsolation} 所描述的业务行为。
     */
    void mysqlToolCallRepositoryKeepsBatchAtomicityAndTenantIsolation() {
        RunToolCall valid = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000001"), RUN_A, TOOL_A);
        RunToolCall invalidTool = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000002"), RUN_A,
                UUID.fromString("20000000-0000-0000-0000-000000000099"));

        assertThatThrownBy(() -> toolCallRepository.saveAll(
                TENANT_A, new RunToolCallBatch(TENANT_A, List.of(valid, invalidTool))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();

        toolCallRepository.saveAll(TENANT_A, new RunToolCallBatch(TENANT_A, List.of(valid)));
        assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A)).containsExactly(valid);
        assertThat(toolCallRepository.listByTenantAndRun(TENANT_B, RUN_A)).isEmpty();
    }

    @Test
    /**
     * 验证 {@code mysqlHttpConfigAndMcpPublicationKeepTenantIsolation} 所描述的业务行为。
     */
    void mysqlHttpConfigAndMcpPublicationKeepTenantIsolation() {
        HttpToolConfig config = JdbcHttpToolConfigRepositoryTest.config(
                TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        McpToolPublication publication = new McpToolPublication(TENANT_A, TOOL_A, true, "tester");

        httpToolConfigRepository.save(config);
        mcpToolPublicationRepository.save(publication);

        assertThat(httpToolConfigRepository.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(config);
        assertThat(httpToolConfigRepository.findByTenantAndToolId(TENANT_B, TOOL_A)).isEmpty();
        assertThat(mcpToolPublicationRepository.listEnabledByTenant(TENANT_A)).containsExactly(publication);
        assertThat(mcpToolPublicationRepository.listEnabledByTenant(TENANT_B)).isEmpty();
    }

    @Test
    /**
     * 验证 {@code mysqlConcurrentFirstSavesAreIdempotent} 所描述的业务行为。
     */
    void mysqlConcurrentFirstSavesAreIdempotent() throws Exception {
        HttpToolConfig first = JdbcHttpToolConfigRepositoryTest.config(
                TENANT_A, TOOL_A, "https://api-a.invalid/v1/{customerId}", Duration.ofSeconds(3));
        HttpToolConfig second = JdbcHttpToolConfigRepositoryTest.config(
                TENANT_A, TOOL_A, "https://api-a.invalid/v2/{customerId}", Duration.ofSeconds(4));
        JdbcHttpToolConfigRepository firstRepository = new JdbcHttpToolConfigRepository(
                JdbcClient.create(dataSource), new com.fasterxml.jackson.databind.ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        JdbcHttpToolConfigRepository secondRepository = new JdbcHttpToolConfigRepository(
                JdbcClient.create(dataSource), new com.fasterxml.jackson.databind.ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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

        assertThat(httpToolConfigRepository.findByTenantAndToolId(TENANT_A, TOOL_A)).get().isIn(first, second);
    }

    @Test
    /**
     * 验证 {@code mysqlConcurrentFirstPublicationSavesAreIdempotent} 所描述的业务行为。
     */
    void mysqlConcurrentFirstPublicationSavesAreIdempotent() throws Exception {
        McpToolPublication enabled = new McpToolPublication(TENANT_A, TOOL_A, true, "tester-a");
        McpToolPublication disabled = new McpToolPublication(TENANT_A, TOOL_A, false, "tester-b");
        JdbcMcpToolPublicationRepository firstRepository = new JdbcMcpToolPublicationRepository(
                JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        JdbcMcpToolPublicationRepository secondRepository = new JdbcMcpToolPublicationRepository(
                JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstSave = executor.submit(() -> savePublicationAfterStart(firstRepository, enabled, ready, start));
            var secondSave = executor.submit(() -> savePublicationAfterStart(secondRepository, disabled, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            firstSave.get(20, TimeUnit.SECONDS);
            secondSave.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(mcpToolPublicationRepository.findByTenantAndToolId(TENANT_A, TOOL_A)).get().isIn(enabled, disabled);
    }

    /**
     * 验证 {@code savePublicationAfterStart} 所描述的业务行为。
     *
     * @param repository 测试仓储
     * @param publication 测试辅助方法使用的 publication 参数
     * @param ready 测试辅助方法使用的 ready 参数
     * @param start 测试辅助方法使用的 start 参数
     */
    private static void savePublicationAfterStart(
            JdbcMcpToolPublicationRepository repository,
            McpToolPublication publication,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        awaitStart(start);
        repository.save(publication);
    }

    /**
     * 验证 {@code saveAfterStart} 所描述的业务行为。
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
     * 验证 {@code awaitStart} 所描述的业务行为。
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
     * 验证 {@code toolCall} 所描述的业务行为。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param runId 测试运行标识
     * @param toolId 测试工具标识
     */
    private static RunToolCall toolCall(UUID id, UUID runId, UUID toolId) {
        return new RunToolCall(id, TENANT_A, runId, toolId, "echo", "input", "output", RunStatus.SUCCEEDED,
                true, 12L, "", Instant.parse("2026-07-14T04:00:00Z"));
    }

    /**
     * 验证 {@code seedData} 所描述的业务行为。
     *
     * @param dataSource 测试数据源
     */
    private static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-14T00:00:00Z"));
        insertTenant(jdbc, TENANT_A, "tenant-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-b", now);
        insertModel(jdbc, MODEL_A, TENANT_A, now);
        insertModel(jdbc, MODEL_B, TENANT_B, now);
        insertAgent(jdbc, AGENT_A, TENANT_A, MODEL_A, now);
        insertAgent(jdbc, AGENT_B, TENANT_B, MODEL_B, now);
        insertTool(jdbc, TOOL_A, TENANT_A, now);
        insertTool(jdbc, TOOL_B, TENANT_B, now);
        insertRun(jdbc, RUN_A, TENANT_A, AGENT_A, now);
        insertRun(jdbc, RUN_B, TENANT_B, AGENT_B, now);
    }

    /**
     * 验证 {@code insertTenant} 所描述的业务行为。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param code 测试辅助方法使用的 code 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTenant(JdbcClient jdbc, UUID id, String code, Timestamp now) {
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", id.toString()).param("code", code).param("name", code).param("createdAt", now).update();
    }

    /**
     * 验证 {@code insertModel} 所描述的业务行为。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertModel(JdbcClient jdbc, UUID id, UUID tenantId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                    encrypted_api_key, enabled, created_at)
                VALUES (:id, :tenantId, 'OPENAI_COMPATIBLE', 'test', 'https://example.invalid', 'test-model',
                    'not-configured', true, :createdAt)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("createdAt", now).update();
    }

    /**
     * 验证 {@code insertAgent} 所描述的业务行为。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param modelId 测试辅助方法使用的 modelId 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertAgent(JdbcClient jdbc, UUID id, UUID tenantId, UUID modelId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                    model_name, temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by,
                    created_at, updated_at)
                VALUES (:id, :tenantId, 'test-agent', '', 'test', :modelId, 'test-model', 0.2, 6, true, '[]',
                    'tester', 'tester', :createdAt, :updatedAt)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("modelId", modelId.toString())
                .param("createdAt", now).param("updatedAt", now).update();
    }

    /**
     * 验证 {@code insertTool} 所描述的业务行为。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTool(JdbcClient jdbc, UUID id, UUID tenantId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema, risk_level,
                    enabled, endpoint, created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenantId, 'echo', 'echo', 'LOCAL', '{}', 'LOW', true, '', 'tester', 'tester',
                    :createdAt, :updatedAt)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("createdAt", now)
                .param("updatedAt", now).update();
    }

    /**
     * 验证 {@code insertRun} 所描述的业务行为。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param agentId 测试 Agent 标识
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertRun(JdbcClient jdbc, UUID id, UUID tenantId, UUID agentId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO runs (id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                    error_message, started_at, finished_at)
                VALUES (:id, :tenantId, :agentId, 'principal', 'RUNNING', 'input', NULL, NULL, :startedAt, NULL)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("startedAt", now).update();
    }
}
