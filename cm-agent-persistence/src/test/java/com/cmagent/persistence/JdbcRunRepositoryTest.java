package com.cmagent.persistence;

import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcRunRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcRunRepository repository;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedAgents(dataSource);
        repository = new JdbcRunRepository(JdbcClient.create(dataSource));
    }

    @Test
    /**
     * 验证或支持 {@code tenantCannotReadAnotherTenantsRun} 所描述的测试场景。
     */
    void tenantCannotReadAnotherTenantsRun() {
        UUID runA = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID runB = UUID.fromString("30000000-0000-0000-0000-000000000002");
        Instant first = Instant.parse("2026-07-14T01:00:00Z");
        Instant second = Instant.parse("2026-07-14T02:00:00Z");

        repository.save(TENANT_A, RunRecord.create(runA, TENANT_A, AGENT_A, "principal-a", "input-a", first));
        repository.save(TENANT_B, RunRecord.create(runB, TENANT_B, AGENT_B, "principal-b", "input-b", second));

        assertThat(repository.findByTenantAndAgentAndId(TENANT_B, AGENT_B, runA)).isEmpty();
        assertThat(repository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(20, null, null)))
                .extracting(RunRecord::id)
                .containsExactly(runA);
    }

    @Test
    /**
     * 验证或支持 {@code listUsesStartedAtAndCanonicalUuidKeysetOrder} 所描述的测试场景。
     */
    void listUsesStartedAtAndCanonicalUuidKeysetOrder() {
        Instant startedAt = Instant.parse("2026-07-14T03:00:00Z");
        UUID lowerId = UUID.fromString("30000000-0000-0000-0000-000000000010");
        UUID higherId = UUID.fromString("30000000-0000-0000-0000-000000000020");
        UUID olderId = UUID.fromString("30000000-0000-0000-0000-000000000030");

        repository.save(TENANT_A, RunRecord.create(lowerId, TENANT_A, AGENT_A, "principal", "lower", startedAt));
        repository.save(TENANT_A, RunRecord.create(higherId, TENANT_A, AGENT_A, "principal", "higher", startedAt));
        repository.save(TENANT_A, RunRecord.create(olderId, TENANT_A, AGENT_A, "principal", "older", startedAt.minusSeconds(1)));

        assertThat(repository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(2, null, null)))
                .extracting(RunRecord::id)
                .containsExactly(higherId, lowerId);
        assertThat(repository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(20, startedAt, higherId)))
                .extracting(RunRecord::id)
                .containsExactly(lowerId, olderId);
    }

    @Test
    /**
     * 验证或支持 {@code saveRejectsExplicitTenantDifferentFromRunTenant} 所描述的测试场景。
     */
    void saveRejectsExplicitTenantDifferentFromRunTenant() {
        RunRecord run = RunRecord.create(
                UUID.fromString("30000000-0000-0000-0000-000000000004"),
                TENANT_A,
                AGENT_A,
                "principal",
                "input",
                Instant.parse("2026-07-14T04:00:00Z")
        );

        assertThatThrownBy(() -> repository.save(TENANT_B, run))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId 与 run.tenantId 不匹配");
    }

    @Test
    /**
     * 验证或支持 {@code completeUpdatesExactlyOneRunningRunInTenant} 所描述的测试场景。
     */
    void completeUpdatesExactlyOneRunningRunInTenant() {
        UUID runId = UUID.fromString("30000000-0000-0000-0000-000000000005");
        Instant startedAt = Instant.parse("2026-07-14T05:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-14T05:00:01Z");
        repository.save(TENANT_A, RunRecord.create(runId, TENANT_A, AGENT_A, "principal", "input", startedAt));

        assertThat(repository.complete(TENANT_A, runId, RunStatus.SUCCEEDED, "output", "", finishedAt))
                .isEqualTo(new RunRecord(runId, TENANT_A, AGENT_A, "principal", RunStatus.SUCCEEDED,
                        "input", "output", "", startedAt, finishedAt));
        assertThatThrownBy(() -> repository.complete(TENANT_B, runId, RunStatus.FAILED, "", "error", finishedAt.plusSeconds(1)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Run 不存在");
    }

    /**
     * 验证或支持 {@code seedAgents} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    private static void seedAgents(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-14T00:00:00Z"));
        insertTenant(jdbcClient, TENANT_A, "tenant-a", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", now);
        insertModelConfig(jdbcClient, UUID.fromString("40000000-0000-0000-0000-000000000001"), TENANT_A, now);
        insertModelConfig(jdbcClient, UUID.fromString("40000000-0000-0000-0000-000000000002"), TENANT_B, now);
        insertAgent(jdbcClient, AGENT_A, TENANT_A, UUID.fromString("40000000-0000-0000-0000-000000000001"), now);
        insertAgent(jdbcClient, AGENT_B, TENANT_B, UUID.fromString("40000000-0000-0000-0000-000000000002"), now);
    }

    /**
     * 验证或支持 {@code insertTenant} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param tenantId 测试租户标识
     * @param code 测试辅助方法使用的 code 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, Timestamp now) {
        jdbcClient.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", code)
                .param("createdAt", now)
                .update();
    }

    /**
     * 验证或支持 {@code insertModelConfig} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param modelId 测试辅助方法使用的 modelId 参数
     * @param tenantId 测试租户标识
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertModelConfig(JdbcClient jdbcClient, UUID modelId, UUID tenantId, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', 'test', 'https://example.invalid',
                            'test-model', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", modelId.toString())
                .param("tenantId", tenantId.toString())
                .param("createdAt", now)
                .update();
    }

    /**
     * 验证或支持 {@code insertAgent} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param agentId 测试 Agent 标识
     * @param tenantId 测试租户标识
     * @param modelId 测试辅助方法使用的 modelId 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertAgent(JdbcClient jdbcClient, UUID agentId, UUID tenantId, UUID modelId, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO agent_definitions (
                            id, tenant_id, name, description, system_prompt, model_provider_id, model_name,
                            temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, 'test-agent', '', 'test', :modelId, 'test-model',
                            0.2, 6, true, '[]', 'tester', 'tester', :createdAt, :updatedAt
                        )
                        """)
                .param("id", agentId.toString())
                .param("tenantId", tenantId.toString())
                .param("modelId", modelId.toString())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }
}
