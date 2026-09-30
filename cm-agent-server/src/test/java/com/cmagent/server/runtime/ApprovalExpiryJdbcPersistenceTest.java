package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.persistence.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 在一次性双数据库中验证系统审批过期的短事务、竞争和重新装配恢复。
 *
 * <p>仅在 Rocky 隔离容器运行；测试不读取应用凭据、不调用模型或外部工具。
 * {@code -Dcm-agent.test.database=mysql} 选择 MySQL 8.4，否则使用 PostgreSQL 16。</p>
 */
@Testcontainers
class ApprovalExpiryJdbcPersistenceTest {
    private static final Instant CUTOFF = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant CREATED = CUTOFF.minusSeconds(60);
    private static final String REQUESTER = "expiry-fixture-user";
    private static final String SYSTEM = "system:approval-expiry";

    // 容器生命周期由 Testcontainers 管理，Flyway 清理只针对本类专用临时库。
    @Container
    static final JdbcDatabaseContainer<?> DATABASE = "mysql".equals(System.getProperty("cm-agent.test.database"))
            ? new MySQLContainer<>("mysql:8.4") : new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcToolApprovalRepository approvals;
    private JdbcRunRepository runs;
    private JdbcRuntimeCheckpointRepository checkpoints;
    private JdbcAuditEventRepository audits;
    private JdbcSkillTrialRepository trials;
    private RunExecutionService runtime;
    private ConversationMessageRepository messages;
    private UUID tenant;
    private UUID agent;
    private UUID tool;
    private UUID skill;
    private UUID version;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        CmAgentFlyway.configure(source).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(source).load().migrate();
        jdbc = JdbcClient.create(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        approvals = new JdbcToolApprovalRepository(jdbc, tx);
        runs = new JdbcRunRepository(jdbc);
        checkpoints = new JdbcRuntimeCheckpointRepository(jdbc);
        audits = new JdbcAuditEventRepository(jdbc, tx);
        trials = new JdbcSkillTrialRepository(jdbc, tx);
        runtime = mock(RunExecutionService.class);
        messages = mock(ConversationMessageRepository.class);
        tenant = UUID.randomUUID();
        agent = UUID.randomUUID();
        tool = UUID.randomUUID();
        seed(tenant, agent, tool);
    }

    @ParameterizedTest
    @EnumSource(ToolApprovalScope.class)
    void auditFailureRollsBackApprovalRunCheckpointAndEarlierAuditThenRetries(ToolApprovalScope scope) {
        ToolApprovalRequest request = fixture(scope, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        AtomicBoolean fail = new AtomicBoolean(true);
        // 第二条审计实际写入后抛错，证明第一条审计和检查点删除也属于同一事务。
        AuditAppender appender = new AuditAppender(audits) {
            @Override
            public void append(UUID tenantId, String principalId, String eventType, String resourceType,
                               String resourceId, String status, String message) {
                super.append(tenantId, principalId, eventType, resourceType, resourceId, status, message);
                if ("TOOL_APPROVAL_EXPIRE".equals(eventType) && fail.get()) {
                    throw new AuditPersistenceException("隔离测试注入审计失败", new IllegalStateException("测试故障"));
                }
            }
        };
        ToolApprovalService service = service(appender);
        assertThatThrownBy(() -> service.expireFromSystem(request, CUTOFF))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(reload(request).status()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(reload(request).version()).isZero();
        assertThat(run(request).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(hasCheckpoint(request)).isTrue();
        assertThat(audits.listByTenant(tenant, 20)).isEmpty();
        if (scope == ToolApprovalScope.RUN) {
            assertThat(trials.find(tenant, request.runId()).orElseThrow().status())
                    .isEqualTo(SkillTrialStatus.WAITING_APPROVAL);
        }

        fail.set(false);
        assertThat(service.expireFromSystem(request, CUTOFF)).isTrue();
        assertClosed(request);
        assertThat(service.expireFromSystem(request, CUTOFF)).isFalse();
        assertThat(audits.listByTenant(tenant, 20)).hasSize(scope == ToolApprovalScope.RUN ? 3 : 2)
                .allSatisfy(event -> assertThat(event.principalId()).isEqualTo(SYSTEM));
        verifyNoInteractions(runtime, messages);
    }

    @ParameterizedTest
    @EnumSource(ToolApprovalScope.class)
    void twoIndependentScannersOnlyCloseAndAuditOnce(ToolApprovalScope scope) throws Exception {
        ToolApprovalRequest request = fixture(scope, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        ToolApprovalService first = service(new AuditAppender(audits));
        ToolApprovalService second = service(new AuditAppender(audits));
        List<Boolean> outcomes = race(() -> first.expireFromSystem(request, CUTOFF),
                () -> second.expireFromSystem(request, CUTOFF));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertClosed(request);
        assertThat(audits.listByTenant(tenant, 20)).hasSize(scope == ToolApprovalScope.RUN ? 3 : 2);
        verifyNoInteractions(runtime, messages);
    }

    @ParameterizedTest
    @EnumSource(ToolApprovalScope.class)
    void scannerAndPreviouslyValidatedDecisionCompeteWithoutDeletingWinnerCheckpoint(ToolApprovalScope scope)
            throws Exception {
        ToolApprovalRequest request = fixture(scope, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        ToolApprovalService scanner = service(new AuditAppender(audits));
        // 决定使用已在到期前取得的服务端时间；两个独立连接竞争同一 PENDING 版本。
        List<Boolean> outcomes = race(() -> scanner.expireFromSystem(request, CUTOFF), () ->
                tx.execute(status -> decide(request, CUTOFF.minusSeconds(1))));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        if (reload(request).status() == ToolApprovalStatus.APPROVED) {
            assertThat(run(request).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
            assertThat(hasCheckpoint(request)).isTrue();
            assertThat(audits.listByTenant(tenant, 20)).isEmpty();
            assertThat(scanner.expireFromSystem(request, CUTOFF)).isFalse();
        } else {
            assertClosed(request);
            assertThat(audits.listByTenant(tenant, 20)).hasSize(scope == ToolApprovalScope.RUN ? 3 : 2);
        }
        verifyNoInteractions(runtime, messages);
    }

    @ParameterizedTest
    @EnumSource(ToolApprovalScope.class)
    void recreatedServiceDiscoversPersistedExpiredApprovalAndClosesIt(ToolApprovalScope scope) {
        ToolApprovalRequest request = fixture(scope, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        // 丢弃原仓储/事务管理器后重建，证明恢复依赖数据库而非上次调度的内存状态。
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var freshJdbc = JdbcClient.create(source);
        var freshTx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var freshApprovals = new JdbcToolApprovalRepository(freshJdbc, freshTx);
        var freshRuns = new JdbcRunRepository(freshJdbc);
        var freshCheckpoints = new JdbcRuntimeCheckpointRepository(freshJdbc);
        var freshAudits = new JdbcAuditEventRepository(freshJdbc, freshTx);
        ToolApprovalService freshService = new ToolApprovalService(freshApprovals, freshRuns, messages, freshCheckpoints,
                mock(RunPersistenceService.class), runtime, mock(PermissionEvaluator.class), new AuditAppender(freshAudits),
                new SensitiveDataRedactor(), new AgentScopeRuntimeProperties(), freshTx,
                new JdbcSkillTrialRepository(freshJdbc, freshTx));
        List<ToolApprovalRequest> candidates = freshApprovals.findExpiredPending(new ApprovalExpiryPage(CUTOFF, null, null, 1));
        assertThat(candidates).extracting(ToolApprovalRequest::id).containsExactly(request.id());
        assertThat(freshService.expireFromSystem(candidates.getFirst(), CUTOFF)).isTrue();
        assertClosed(request);
        verifyNoInteractions(runtime, messages);
    }

    @Test
    void runningAndTerminalRunsKeepCheckpointEvenWhenTheirPendingApprovalExpires() {
        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.SUCCEEDED, RunStatus.DENIED, RunStatus.FAILED)) {
            ToolApprovalRequest request = fixture(ToolApprovalScope.RUN, status, CUTOFF, UUID.randomUUID());
            assertThat(service(new AuditAppender(audits)).expireFromSystem(request, CUTOFF)).isTrue();
            assertThat(reload(request).status()).isEqualTo(ToolApprovalStatus.EXPIRED);
            assertThat(run(request).status()).isEqualTo(status);
            assertThat(hasCheckpoint(request)).isTrue();
        }
        assertThat(audits.listByTenant(tenant, 20)).hasSize(4)
                .allSatisfy(event -> assertThat(event.eventType()).isEqualTo("TOOL_APPROVAL_EXPIRE"));
        verifyNoInteractions(runtime, messages);
    }

    @ParameterizedTest
    @EnumSource(ToolApprovalScope.class)
    void approvedApprovalIsNeverClosedOrCleanedByStaleScanner(ToolApprovalScope scope) {
        ToolApprovalRequest staleCandidate = fixture(scope, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        assertThat(decide(staleCandidate, CUTOFF.minusSeconds(1))).isTrue();
        assertThat(service(new AuditAppender(audits)).expireFromSystem(staleCandidate, CUTOFF)).isFalse();
        assertThat(reload(staleCandidate).status()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(run(staleCandidate).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(hasCheckpoint(staleCandidate)).isTrue();
        assertThat(audits.listByTenant(tenant, 20)).isEmpty();
        verifyNoInteractions(runtime, messages);
    }

    @Test
    void waitingRunSnapshotCannotCloseRunThatBecameRunning() {
        ToolApprovalRequest request = fixture(ToolApprovalScope.RUN, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        RunRecord staleRun = run(request);
        // 模拟读等待态与条件写入之间的恢复，不调用 Runtime 或任何具有外部副作用的工具。
        jdbc.sql("UPDATE runs SET status = 'RUNNING' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.toString()).param("id", request.runId().toString()).update();
        assertThat(runs.expireWaitingApproval(staleRun, CUTOFF)).isFalse();
        assertThat(run(request).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(hasCheckpoint(request)).isTrue();
    }

    @Test
    void mismatchedTrialRequesterRollsBackEntireExpiryTransaction() {
        ToolApprovalRequest request = fixture(ToolApprovalScope.RUN, RunStatus.WAITING_APPROVAL, CUTOFF, UUID.randomUUID());
        // 故意制造数据库中关联主体不一致的坏数据，系统身份不能借此收口其他主体的试运行。
        jdbc.sql("UPDATE skill_trials SET created_by = 'different-fixture-user' WHERE tenant_id = :tenant AND run_id = :run")
                .param("tenant", tenant.toString()).param("run", request.runId().toString()).update();
        assertThatThrownBy(() -> service(new AuditAppender(audits)).expireFromSystem(request, CUTOFF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("归属不一致");
        assertThat(reload(request).status()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(reload(request).version()).isZero();
        assertThat(run(request).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(hasCheckpoint(request)).isTrue();
        assertThat(trials.find(tenant, request.runId()).orElseThrow().status()).isEqualTo(SkillTrialStatus.WAITING_APPROVAL);
        assertThat(audits.listByTenant(tenant, 20)).isEmpty();
        verifyNoInteractions(runtime, messages);
    }

    @Test
    void cutoffAndSameTimeKeysetPaginationCoverBothScopesAcrossTenants() {
        ToolApprovalRequest low = fixture(ToolApprovalScope.CONVERSATION, RunStatus.WAITING_APPROVAL, CUTOFF,
                UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ToolApprovalRequest high = fixture(ToolApprovalScope.RUN, RunStatus.WAITING_APPROVAL, CUTOFF,
                UUID.fromString("f0000000-0000-0000-0000-000000000001"));
        fixture(ToolApprovalScope.RUN, RunStatus.WAITING_APPROVAL, CUTOFF.plusSeconds(1), UUID.randomUUID());
        UUID firstTenant = tenant;
        tenant = UUID.randomUUID(); agent = UUID.randomUUID(); tool = UUID.randomUUID();
        seed(tenant, agent, tool);
        ToolApprovalRequest otherTenant = fixture(ToolApprovalScope.RUN, RunStatus.WAITING_APPROVAL, CUTOFF,
                UUID.fromString("80000000-0000-0000-0000-000000000001"));
        List<ToolApprovalRequest> first = approvals.findExpiredPending(new ApprovalExpiryPage(CUTOFF, null, null, 1));
        assertThat(first).extracting(ToolApprovalRequest::id).containsExactly(low.id());
        List<ToolApprovalRequest> second = approvals.findExpiredPending(new ApprovalExpiryPage(CUTOFF, CUTOFF, low.id(), 1));
        assertThat(second).extracting(ToolApprovalRequest::id).containsExactly(otherTenant.id());
        assertThat(approvals.findExpiredPending(new ApprovalExpiryPage(CUTOFF, CUTOFF, otherTenant.id(), 1)))
                .extracting(ToolApprovalRequest::id).containsExactly(high.id());
        assertThat(approvals.findExpiredPending(new ApprovalExpiryPage(CUTOFF, CUTOFF, high.id(), 1))).isEmpty();
        assertThat(approvals.findByRun(firstTenant, otherTenant.agentId(), otherTenant.runId(), otherTenant.id())).isEmpty();
        assertThat(approvals.findByRun(tenant, agent, UUID.randomUUID(), otherTenant.id())).isEmpty();
        assertThat(decide(otherTenant, CUTOFF)).isFalse();
    }

    private ToolApprovalService service(AuditAppender appender) {
        return new ToolApprovalService(approvals, runs, messages, checkpoints, mock(RunPersistenceService.class), runtime,
                mock(PermissionEvaluator.class), appender, new SensitiveDataRedactor(), new AgentScopeRuntimeProperties(), tx, trials);
    }

    private ToolApprovalRequest fixture(ToolApprovalScope scope, RunStatus status, Instant expiresAt, UUID approvalId) {
        UUID runId = UUID.randomUUID();
        UUID conversationId = scope == ToolApprovalScope.CONVERSATION ? UUID.randomUUID() : null;
        if (conversationId != null) {
            new JdbcConversationRepository(jdbc).save(tenant,
                    new Conversation(conversationId, tenant, agent, "隔离审批测试", REQUESTER, CREATED, CREATED));
        }
        RunRecord run = runs.save(tenant, RunRecord.create(runId, tenant, agent, REQUESTER,
                scope == ToolApprovalScope.RUN ? RunKind.TEST : RunKind.NORMAL, "隔离输入", CREATED));
        if (status == RunStatus.WAITING_APPROVAL) runs.waitForApproval(tenant, runId);
        else if (status != RunStatus.RUNNING) runs.complete(tenant, run.id(), status, "", "", CUTOFF);
        if (scope == ToolApprovalScope.RUN && status == RunStatus.WAITING_APPROVAL) {
            trials.insert(new SkillTrial(runId, tenant, skill, version, agent, 0,
                    SkillTrialStatus.WAITING_APPROVAL, false, REQUESTER, CREATED, CREATED));
        }
        checkpoints.save(new RuntimeCheckpoint(UUID.randomUUID(), tenant, tenant + ":" + REQUESTER, runId.toString(),
                "agent-state", "fixture", false, "仅测试占位密文，不用于运行恢复", CUTOFF.plusSeconds(3600), CREATED, CREATED));
        ToolApprovalItem item = new ToolApprovalItem(UUID.randomUUID(), approvalId, "fixture-call", tool, "expiry_tool",
                ToolRiskLevel.HIGH, "脱敏测试摘要", "a".repeat(64), ToolApprovalPolicy.EACH_CALL, 1, null);
        return approvals.save(new ToolApprovalRequest(approvalId, tenant, agent, scope, conversationId, runId,
                REQUESTER, "隔离发起人", ToolApprovalStatus.PENDING, expiresAt, 0, runId.toString(), CREATED, CREATED,
                null, null, null, List.of(item)));
    }

    private boolean decide(ToolApprovalRequest request, Instant at) {
        Map<UUID, ToolApprovalDecision> decisions = Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE);
        return request.scope() == ToolApprovalScope.RUN
                ? approvals.decideByRun(request.tenantId(), request.agentId(), request.runId(), request.id(), request.version(),
                        ToolApprovalStatus.APPROVED, decisions, REQUESTER, "隔离发起人", at)
                : approvals.decide(request.tenantId(), request.agentId(), request.conversationId(), request.id(), request.version(),
                        ToolApprovalStatus.APPROVED, decisions, REQUESTER, "隔离发起人", at);
    }

    private ToolApprovalRequest reload(ToolApprovalRequest request) {
        return request.scope() == ToolApprovalScope.RUN
                ? approvals.findByRun(request.tenantId(), request.agentId(), request.runId(), request.id()).orElseThrow()
                : approvals.find(request.tenantId(), request.agentId(), request.conversationId(), request.id()).orElseThrow();
    }

    private RunRecord run(ToolApprovalRequest request) {
        return runs.findByTenantAndAgentAndId(request.tenantId(), request.agentId(), request.runId()).orElseThrow();
    }

    private boolean hasCheckpoint(ToolApprovalRequest request) {
        return checkpoints.exists(request.tenantId(), request.tenantId() + ":" + REQUESTER, request.runId().toString());
    }

    private void assertClosed(ToolApprovalRequest request) {
        assertThat(reload(request).status()).isEqualTo(ToolApprovalStatus.EXPIRED);
        assertThat(reload(request).version()).isEqualTo(1);
        assertThat(reload(request).decidedBy()).isEqualTo(SYSTEM);
        assertThat(run(request).status()).isEqualTo(RunStatus.DENIED);
        assertThat(run(request).errorMessage()).contains("审批已过期");
        assertThat(hasCheckpoint(request)).isFalse();
        if (request.scope() == ToolApprovalScope.RUN) {
            assertThat(trials.find(request.tenantId(), request.runId()).orElseThrow())
                    .satisfies(trial -> {
                        assertThat(trial.status()).isEqualTo(SkillTrialStatus.FAILED);
                        assertThat(trial.qualifiesRelease()).isFalse();
                        assertThat(trial.createdBy()).isEqualTo(REQUESTER);
                    });
        }
    }

    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var two = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                // setup 断言失败时也解除等待，使执行器关闭不会无限等待子任务。
                start.countDown();
            }
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }

    private void seed(UUID tenantId, UUID agentId, UUID toolId) {
        UUID modelId = UUID.randomUUID();
        Timestamp at = Timestamp.from(CREATED);
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, '隔离审批', true, :at)")
                .param("id", tenantId.toString()).param("code", "expiry-" + tenantId).param("at", at).update();
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                    encrypted_api_key, enabled, created_at)
                VALUES (:id, :tenant, 'OPENAI_COMPATIBLE', '隔离审批', 'https://example.invalid', 'fixture',
                    'not-configured', true, :at)
                """).param("id", modelId.toString()).param("tenant", tenantId.toString()).param("at", at).update();
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                    model_name, temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenant, '隔离审批', '', '测试', :model, 'fixture', 0.2, 6, true, '[]', 'tester', 'tester', :at, :at)
                """).param("id", agentId.toString()).param("tenant", tenantId.toString())
                .param("model", modelId.toString()).param("at", at).update();
        jdbc.sql("""
                INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema, risk_level,
                    enabled, endpoint, created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenant, 'expiry_tool', '隔离测试工具', 'LOCAL', '{}', 'HIGH', true, '', 'tester', 'tester', :at, :at)
                """).param("id", toolId.toString()).param("tenant", tenantId.toString()).param("at", at).update();
        skill = UUID.randomUUID();
        version = UUID.randomUUID();
        new JdbcSkillDefinitionRepository(jdbc).insert(new SkillDefinition(skill, tenantId, "expiry-fixture", version,
                null, true, 0, 0, REQUESTER, REQUESTER, CREATED, CREATED));
        new JdbcSkillVersionRepository(jdbc, new ObjectMapper()).insert(new SkillVersion(version, tenantId, skill, 1,
                "隔离试运行版本", Map.of(), "隔离测试指令", "a".repeat(64), REQUESTER, CREATED));
    }
}
