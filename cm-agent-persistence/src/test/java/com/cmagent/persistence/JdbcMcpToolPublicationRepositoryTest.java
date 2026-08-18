package com.cmagent.persistence;

import com.cmagent.core.domain.McpToolPublication;
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
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        JdbcHttpToolConfigRepositoryTest.seedData(dataSource);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcHttpToolConfigRepositoryTest.insertTool(jdbc, TOOL_A_DISABLED, TENANT_A, "http-a-disabled",
                new java.sql.Timestamp(java.time.Instant.parse("2026-07-21T00:00:00Z").toEpochMilli()));
        repository = repository(dataSource);
    }

    @Test
    /**
     * 验证列表查询能够返回 {@code OnlyEnabledPublicationsForTheCurrentTenant}。
     */
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
    /**
     * 验证更新流程能够处理 {@code AndDeletesOnlyTheTargetTenantPublication}。
     */
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

    @Test
    /**
     * 验证或支持 {@code bulkFindUsesTenantScope} 所描述的测试场景。
     */
    void bulkFindUsesTenantScope() {
        McpToolPublication publicationA = new McpToolPublication(TENANT_A, TOOL_A, true, "admin-a");
        McpToolPublication publicationB = new McpToolPublication(TENANT_B, TOOL_B, true, "admin-b");
        repository.save(publicationA);
        repository.save(publicationB);

        assertThat(repository.findByTenantAndToolIds(TENANT_A, List.of(TOOL_A, TOOL_B)))
                .containsExactly(Map.entry(TOOL_A, publicationA));
    }

    @Test
    /**
     * 验证或支持 {@code concurrentFirstSavesAreIdempotent} 所描述的测试场景。
     */
    void concurrentFirstSavesAreIdempotent() throws Exception {
        McpToolPublication enabled = new McpToolPublication(TENANT_A, TOOL_A, true, "admin-a");
        McpToolPublication disabled = new McpToolPublication(TENANT_A, TOOL_A, false, "admin-b");
        JdbcMcpToolPublicationRepository firstRepository = repository(dataSource);
        JdbcMcpToolPublicationRepository secondRepository = repository(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstSave = executor.submit(() -> saveAfterStart(firstRepository, enabled, ready, start));
            var secondSave = executor.submit(() -> saveAfterStart(secondRepository, disabled, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            firstSave.get(20, TimeUnit.SECONDS);
            secondSave.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.findByTenantAndToolId(TENANT_A, TOOL_A)).get().isIn(enabled, disabled);
    }

    /**
     * 验证或支持 {@code saveAfterStart} 所描述的测试场景。
     *
     * @param repository 测试仓储
     * @param publication 测试辅助方法使用的 publication 参数
     * @param ready 测试辅助方法使用的 ready 参数
     * @param start 测试辅助方法使用的 start 参数
     */
    private static void saveAfterStart(
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
    private static JdbcMcpToolPublicationRepository repository(DataSource dataSource) {
        return new JdbcMcpToolPublicationRepository(
                JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }
}
