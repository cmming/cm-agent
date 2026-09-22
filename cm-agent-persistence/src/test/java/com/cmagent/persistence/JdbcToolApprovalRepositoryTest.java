package com.cmagent.persistence;

import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunKind;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalItem;
import com.cmagent.core.domain.ToolApprovalPolicy;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.repository.ToolApprovalRepository;
import com.cmagent.core.domain.ToolApprovalScope;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.persistence.JdbcToolApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcToolApprovalRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolApprovalRepository repository;

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedAgents(dataSource);
        repository = new JdbcToolApprovalRepository(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void runtimeApprovalIsProtectedInsideRunScope() {
        UUID run = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID tool = UUID.fromString("30000000-0000-0000-0000-000000000003");
        ToolApprovalRequest request = request(approvalId, TENANT_A, AGENT_A, run, tool);
        new JdbcRunRepository(JdbcClient.create(dataSource())).save(TENANT_A, RunRecord.create(
                run, TENANT_A, AGENT_A, "principal", RunKind.TEST, "candidate", NOW));
        repository.save(request);

        assertThat(repository.findByRun(TENANT_A, AGENT_A, run, approvalId).orElseThrow())
                .isEqualTo(request);
        assertThat(repository.findByRun(TENANT_B, AGENT_A, run, approvalId)).isEmpty();
        assertThat(repository.listPendingByRun(TENANT_A, AGENT_A, run, NOW, 10))
                .extracting(ToolApprovalRequest::id).containsExactly(approvalId);
        assertThat(repository.hasPendingByRun(TENANT_A, AGENT_A, run, NOW)).isTrue();
        assertThat(repository.decideByRun(
                TENANT_A, AGENT_A, run, approvalId, 0, ToolApprovalStatus.EXPIRED,
                Map.of(UUID.randomUUID(), ToolApprovalDecision.APPROVE),
                "tester", "审批人", NOW.plusSeconds(1))).isFalse();
        assertThat(repository.hasPendingByRun(TENANT_A, AGENT_A, run, NOW)).isTrue();
    }

    private static void seedAgents(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp timestamp = Timestamp.from(NOW);
        insertTenant(jdbc, TENANT_A, timestamp);
        insertTenant(jdbc, TENANT_B, timestamp);
        insertModelConfig(jdbc, UUID.fromString("10000000-0000-0000-0000-000000000001"), TENANT_A, timestamp);
        insertModelConfig(jdbc, UUID.fromString("10000000-0000-0000-0000-000000000002"), TENANT_B, timestamp);
        insertAgent(jdbc, AGENT_A, TENANT_A, UUID.fromString("10000000-0000-0000-0000-000000000001"), timestamp);
        insertAgent(jdbc, AGENT_B, TENANT_B, UUID.fromString("10000000-0000-0000-0000-000000000002"), timestamp);
        jdbc.sql("""
                INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema,
                    risk_level, enabled, endpoint, created_by, updated_by, created_at, updated_at)
                VALUES ('30000000-0000-0000-0000-000000000003', :tenantId, 'run_tool', '', 'LOCAL', '{}',
                    'HIGH', true, '', 'tester', 'tester', :createdAt, :updatedAt)
                """).param("tenantId", TENANT_A.toString())
                .param("createdAt", timestamp).param("updatedAt", timestamp).update();
    }

    private static ToolApprovalRequest request(
            UUID approvalId, UUID tenantId, UUID agentId, UUID runId, UUID toolId) {
        ToolApprovalItem item = new ToolApprovalItem(
                UUID.randomUUID(), approvalId, "call-run-1", toolId, "run_tool",
                ToolRiskLevel.HIGH, "input", "a".repeat(64),
                ToolApprovalPolicy.EACH_CALL, 0, null);
        return new ToolApprovalRequest(
                approvalId, tenantId, agentId, ToolApprovalScope.RUN, null, runId,
                "principal", "运行级测试", ToolApprovalStatus.PENDING, NOW.plusSeconds(900), 0,
                runId.toString(), NOW, NOW, null, null, null, List.of(item));
    }

    private static void insertTenant(JdbcClient jdbc, UUID tenantId, Timestamp timestamp) {
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, '审批', true, :createdAt)")
                .param("id", tenantId.toString()).param("code", "approval-" + tenantId)
                .param("createdAt", timestamp).update();
    }

    private static void insertModelConfig(JdbcClient jdbc, UUID modelId, UUID tenantId, Timestamp timestamp) {
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                    encrypted_api_key, enabled, created_at)
                VALUES (:id, :tenantId, 'OPENAI_COMPATIBLE', '审批', 'https://example.invalid', 'model',
                    'not-configured', true, :createdAt)
                """).param("id", modelId.toString()).param("tenantId", tenantId.toString())
                .param("createdAt", timestamp).update();
    }

    private static void insertAgent(
            JdbcClient jdbc, UUID agentId, UUID tenantId, UUID modelId, Timestamp timestamp) {
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                    model_name, temperature, max_iterations, enabled, tool_ids_json,
                    created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenantId, '审批', '', 'test', :modelId, 'model', 0.2, 6, true, '[]',
                    'tester', 'tester', :createdAt, :updatedAt)
                """).param("id", agentId.toString()).param("tenantId", tenantId.toString())
                .param("modelId", modelId.toString())
                .param("createdAt", timestamp).param("updatedAt", timestamp).update();
    }
}
