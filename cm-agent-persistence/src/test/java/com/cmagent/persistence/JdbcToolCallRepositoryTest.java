package com.cmagent.persistence;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcToolCallRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID RUN_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID CONTROL_RUN = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID TOOL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolCallRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        repository = new JdbcToolCallRepository(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    void toolCallReadRequiresSameTenantAndRun() {
        RunToolCall first = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000010"), TENANT_A, RUN_A, TOOL_A,
                Instant.parse("2026-07-14T01:00:00Z"));
        RunToolCall second = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000020"), TENANT_A, RUN_A, TOOL_A,
                Instant.parse("2026-07-14T01:00:00Z"));

        repository.saveAll(TENANT_A, new RunToolCallBatch(TENANT_A, List.of(second, first)));

        assertThat(repository.listByTenantAndRun(TENANT_A, RUN_A)).containsExactly(first, second);
        assertThat(repository.listByTenantAndRun(TENANT_B, RUN_A)).isEmpty();
        assertThat(repository.listByTenantAndRun(TENANT_A, RUN_B)).isEmpty();
    }

    @Test
    void mixedTenantToolCallBatchIsRejectedBeforeJdbcWrite() {
        RunToolCall callA = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000030"), TENANT_A, RUN_A, TOOL_A,
                Instant.parse("2026-07-14T02:00:00Z"));
        RunToolCall callB = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000040"), TENANT_B, RUN_B, TOOL_B,
                Instant.parse("2026-07-14T02:00:01Z"));

        assertThatThrownBy(() -> new RunToolCallBatch(TENANT_A, List.of(callA, callB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCalls 必须全部属于 tenantId");
        assertThat(repository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();
    }

    @Test
    void repositoryTenantMismatchIsRejectedBeforeAnyJdbcWrite() {
        RunToolCall call = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000050"), TENANT_A, RUN_A, TOOL_A,
                Instant.parse("2026-07-14T03:00:00Z"));

        assertThatThrownBy(() -> repository.saveAll(TENANT_B, new RunToolCallBatch(TENANT_A, List.of(call))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId 与 toolCalls 批次不匹配");
        assertThat(repository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();
    }

    @Test
    void saveAllRollsBackEarlierToolCallsWhenLaterToolCallViolatesForeignKey() {
        RunToolCall control = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000055"), TENANT_A, CONTROL_RUN, TOOL_A,
                Instant.parse("2026-07-14T03:30:00Z"));
        RunToolCall valid = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000060"), TENANT_A, RUN_A, TOOL_A,
                Instant.parse("2026-07-14T04:00:00Z"));
        RunToolCall invalid = toolCall(UUID.fromString("50000000-0000-0000-0000-000000000070"), TENANT_A, RUN_A,
                UUID.fromString("20000000-0000-0000-0000-000000000099"), Instant.parse("2026-07-14T04:00:01Z"));

        repository.saveAll(TENANT_A, new RunToolCallBatch(TENANT_A, List.of(control)));
        assertThat(repository.listByTenantAndRun(TENANT_A, CONTROL_RUN)).containsExactly(control);

        DataIntegrityViolationException exception = catchThrowableOfType(
                () -> repository.saveAll(TENANT_A, new RunToolCallBatch(TENANT_A, List.of(valid, invalid))),
                DataIntegrityViolationException.class
        );
        assertThat(exception).isNotNull();
        PSQLException postgresqlException = findPostgresqlException(exception);
        assertThat(postgresqlException.getSQLState()).isEqualTo("23503");
        assertThat(postgresqlException.getServerErrorMessage()).isNotNull();
        assertThat(postgresqlException.getServerErrorMessage().getConstraint()).isEqualTo("fk_tool_calls_tool");

        assertThat(repository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();
    }

    private static PSQLException findPostgresqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PSQLException postgresqlException) {
                return postgresqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("缺少 PostgreSQL 外键异常", throwable);
    }

    private static RunToolCall toolCall(UUID id, UUID tenantId, UUID runId, UUID toolId, Instant createdAt) {
        return new RunToolCall(id, tenantId, runId, toolId, "echo", "input", "output", RunStatus.SUCCEEDED,
                true, 12L, "", createdAt);
    }

    private static void seedData(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-14T00:00:00Z"));
        insertTenant(jdbcClient, TENANT_A, "tenant-a", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", now);
        insertModelConfig(jdbcClient, MODEL_A, TENANT_A, now);
        insertModelConfig(jdbcClient, MODEL_B, TENANT_B, now);
        insertAgent(jdbcClient, AGENT_A, TENANT_A, MODEL_A, now);
        insertAgent(jdbcClient, AGENT_B, TENANT_B, MODEL_B, now);
        insertRun(jdbcClient, RUN_A, TENANT_A, now);
        insertRun(jdbcClient, RUN_B, TENANT_B, now);
        insertRun(jdbcClient, CONTROL_RUN, TENANT_A, now);
        insertTool(jdbcClient, TOOL_A, TENANT_A, now);
        insertTool(jdbcClient, TOOL_B, TENANT_B, now);
    }

    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, Timestamp now) {
        jdbcClient.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", code)
                .param("createdAt", now)
                .update();
    }

    private static void insertRun(JdbcClient jdbcClient, UUID runId, UUID tenantId, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO runs (
                            id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                            error_message, started_at, finished_at
                        ) VALUES (
                            :id, :tenantId, :agentId, 'principal', 'RUNNING', 'input', NULL,
                            NULL, :startedAt, NULL
                        )
                        """)
                .param("id", runId.toString())
                .param("tenantId", tenantId.toString())
                .param("agentId", (tenantId.equals(TENANT_A) ? AGENT_A : AGENT_B).toString())
                .param("startedAt", now)
                .update();
    }

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

    private static void insertTool(JdbcClient jdbcClient, UUID toolId, UUID tenantId, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO tool_definitions (
                            id, tenant_id, name, description, type, input_schema, risk_level, enabled,
                            endpoint, created_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, 'echo', 'echo', 'LOCAL', '{}', 'LOW', true,
                            '', 'tester', 'tester', :createdAt, :updatedAt
                        )
                        """)
                .param("id", toolId.toString())
                .param("tenantId", tenantId.toString())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }
}
