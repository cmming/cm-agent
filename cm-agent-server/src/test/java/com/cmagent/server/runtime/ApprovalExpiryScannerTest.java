package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.config.ApprovalExpiryProperties;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.cmagent.server.store.InMemoryToolApprovalRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalExpiryScannerTest {
    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

    @Test
    void 真实内存技能仓储通过工作单元同步收口等待试运行() {
        var approvals = new InMemoryToolApprovalRepository();
        var runs = new InMemoryPlatformStore();
        var skills = new com.cmagent.server.store.InMemorySkillStore();
        var request = request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW);
        approvals.save(request);
        runs.save(request.tenantId(), RunRecord.create(request.runId(), request.tenantId(), request.agentId(),
                "initiator", RunKind.TEST, "受控验收", NOW.minusSeconds(20)));
        runs.waitForApproval(request.tenantId(), request.runId());
        skills.execute(() -> skills.trials().insert(new SkillTrial(request.runId(), request.tenantId(),
                UUID.randomUUID(), UUID.randomUUID(), request.agentId(), 0, SkillTrialStatus.WAITING_APPROVAL,
                false, "initiator", NOW.minusSeconds(20), NOW.minusSeconds(20))));
        var audit = mock(AuditAppender.class);
        var execution = mock(RunExecutionService.class);
        var service = new ToolApprovalService(approvals, runs, mock(ConversationMessageRepository.class),
                mock(RuntimeCheckpointRepository.class), mock(RunPersistenceService.class), execution,
                (actor, permission) -> com.cmagent.core.security.AuthorizationDecision.deny("系统不执行工具"),
                audit, new SensitiveDataRedactor(), new AgentScopeRuntimeProperties(), null, skills.trials(), skills);
        assertThat(service.expireFromSystem(request, NOW)).isTrue();
        assertThat(skills.trials().find(request.tenantId(), request.runId()).orElseThrow().status())
                .isEqualTo(SkillTrialStatus.FAILED);
        assertThat(service.expireFromSystem(request, NOW)).isFalse();
        verifyNoInteractions(execution);
    }

    @Test
    void 失败诊断包含可信归属编号和脱敏堆栈() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ErrorDiagnosticLogger.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            var repository = new InMemoryToolApprovalRepository();
            var request = request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW);
            repository.save(request);
            var service = mock(ToolApprovalService.class);
            when(service.expireFromSystem(request, NOW)).thenThrow(new IllegalStateException(
                    "apiKey=private-marker http://internal.invalid/private SELECT secret FROM hidden"));
            var diagnostics = new ErrorDiagnosticLogger(new SensitiveDataRedactor(), new ToolOutputSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()));
            scanner(repository, service, new ApprovalExpiryProperties(), diagnostics).scan();
            assertThat(capture.list).hasSize(1);
            var event = capture.list.getFirst();
            assertThat(event.getFormattedMessage()).contains("errorId=", "APPROVAL_EXPIRY_FAILED", "operation=expire",
                    request.tenantId().toString(), request.runId().toString(), request.id().toString())
                    .doesNotContain("private-marker", "internal.invalid", "SELECT secret");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).doesNotContain("private-marker", "internal.invalid", "SELECT secret");
        } finally {
            logger.detachAppender(capture); capture.stop();
        }
    }

    @Test
    void 两种范围主动过期使用系统审计身份且只删原Run检查点() {
        var repository = new InMemoryToolApprovalRepository();
        var runs = new InMemoryPlatformStore();
        var checkpoints = mock(RuntimeCheckpointRepository.class);
        var audit = mock(AuditAppender.class);
        var execution = mock(RunExecutionService.class);
        var service = service(repository, runs, checkpoints, audit, execution);
        for (var scope : ToolApprovalScope.values()) {
            var request = request(scope, UUID.randomUUID(), UUID.randomUUID(), NOW);
            repository.save(request);
            runs.save(request.tenantId(), RunRecord.create(request.runId(), request.tenantId(), request.agentId(),
                    "initiator", scope == ToolApprovalScope.RUN ? RunKind.TEST : RunKind.NORMAL, "测试", NOW.minusSeconds(20)));
            runs.waitForApproval(request.tenantId(), request.runId());
        }
        var candidates = repository.findExpiredPending(new ApprovalExpiryPage(NOW, null, null, 100));
        var scanner = scanner(repository, service, new ApprovalExpiryProperties(), mock(ErrorDiagnosticLogger.class));
        scanner.scan();
        scanner.scan();
        for (var request : candidates) {
            var run = runs.findByTenantAndAgentAndId(request.tenantId(), request.agentId(), request.runId()).orElseThrow();
            assertThat(run.status()).isEqualTo(RunStatus.DENIED);
            assertThat(run.errorMessage()).isEqualTo("工具审批已过期");
            verify(checkpoints).deleteSession(request.tenantId(), request.tenantId() + ":initiator", request.runId().toString());
            verify(audit).append(request.tenantId(), "system:approval-expiry", "TOOL_APPROVAL_EXPIRE", "TOOL_APPROVAL",
                    request.id().toString(), "EXPIRED", "高风险工具审批请求已过期");
        }
        verifyNoInteractions(execution);
        assertThat(repository.findExpiredPending(new ApprovalExpiryPage(NOW, null, null, 100))).isEmpty();
    }

    @Test
    void 竞争输家与已运行或终态Run都不能删除检查点() {
        var repository = new InMemoryToolApprovalRepository();
        var runs = mock(RunRepository.class);
        var checkpoints = mock(RuntimeCheckpointRepository.class);
        var audit = mock(AuditAppender.class);
        var execution = mock(RunExecutionService.class);
        var service = service(repository, runs, checkpoints, audit, execution);
        var request = request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository.save(request);
        assertThat(repository.decideByRun(request.tenantId(), request.agentId(), request.runId(), request.id(), 0,
                ToolApprovalStatus.APPROVED, Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "initiator", "测试", NOW.minusSeconds(1))).isTrue();
        assertThat(service.expireFromSystem(request, NOW)).isFalse();
        verifyNoInteractions(runs, checkpoints, audit, execution);
        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.SUCCEEDED)) {
            var stale = request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW);
            repository.save(stale);
            var run = RunRecord.create(stale.runId(), stale.tenantId(), stale.agentId(), "initiator", "测试", NOW.minusSeconds(20));
            if (status == RunStatus.SUCCEEDED) run = run.complete(status, "结果", "", NOW.minusSeconds(1));
            when(runs.findByTenantAndAgentAndId(stale.tenantId(), stale.agentId(), stale.runId())).thenReturn(Optional.of(run));
            assertThat(service.expireFromSystem(stale, NOW)).isTrue();
        }
        verifyNoInteractions(checkpoints, execution);
        verify(runs, never()).expireWaitingApproval(any(), any());
    }

    @Test
    void 失败项不会饿死后续租户且遍历结束后重试() {
        var repository = new InMemoryToolApprovalRepository();
        var failed = request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.fromString("10000000-0000-0000-0000-000000000001"), NOW);
        var next = request(ToolApprovalScope.CONVERSATION, UUID.randomUUID(), UUID.fromString("f0000000-0000-0000-0000-000000000001"), NOW);
        repository.save(failed); repository.save(next);
        var service = mock(ToolApprovalService.class);
        when(service.expireFromSystem(failed, NOW)).thenThrow(new IllegalStateException("审计不可用"));
        var diagnostics = mock(ErrorDiagnosticLogger.class);
        var properties = new ApprovalExpiryProperties(); properties.setBatchSize(1);
        var scanner = scanner(repository, service, properties, diagnostics);
        scanner.scan(); scanner.scan(); scanner.scan(); scanner.scan();
        verify(service, times(2)).expireFromSystem(failed, NOW);
        verify(service).expireFromSystem(next, NOW);
        verify(diagnostics, times(2)).error(argThat(context -> context.errorCode().equals("APPROVAL_EXPIRY_FAILED")
                && context.tenantId().equals(failed.tenantId().toString())
                && context.source().contains(failed.id().toString()) && !context.errorId().isBlank()), any(Throwable.class));
    }

    @Test
    void 停用不查询且非法配置拒绝初始化() {
        var repository = mock(ToolApprovalRepository.class);
        var properties = new ApprovalExpiryProperties(); properties.setEnabled(false);
        scanner(repository, mock(ToolApprovalService.class), properties, mock(ErrorDiagnosticLogger.class)).scan();
        verifyNoInteractions(repository);
        properties.setBatchSize(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setBatchSize(101);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setBatchSize(100);
        for (Duration duration : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(2))) {
            properties.setInterval(duration);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        }
    }

    private static ApprovalExpiryScanner scanner(ToolApprovalRepository repository, ToolApprovalService service,
            ApprovalExpiryProperties properties, ErrorDiagnosticLogger diagnostics) {
        return new ApprovalExpiryScanner(repository, service, properties, diagnostics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ToolApprovalService service(ToolApprovalRepository repository, RunRepository runs,
            RuntimeCheckpointRepository checkpoints, AuditAppender audit, RunExecutionService execution) {
        return new ToolApprovalService(repository, runs, mock(ConversationMessageRepository.class), checkpoints,
                mock(RunPersistenceService.class), execution, (actor, permission) ->
                com.cmagent.core.security.AuthorizationDecision.deny("系统任务没有工具权限"), audit,
                new SensitiveDataRedactor(), new AgentScopeRuntimeProperties(), null);
    }

    static ToolApprovalRequest request(ToolApprovalScope scope, UUID tenant, UUID id, Instant expiry) {
        return new ToolApprovalRequest(id, tenant, UUID.randomUUID(), scope,
                scope == ToolApprovalScope.CONVERSATION ? UUID.randomUUID() : null, UUID.randomUUID(),
                "initiator", "测试发起人", ToolApprovalStatus.PENDING, expiry, 0, "checkpoint",
                expiry.minusSeconds(20), expiry.minusSeconds(20), null, null, null,
                List.of(new ToolApprovalItem(UUID.randomUUID(), id, "call", UUID.randomUUID(), "测试工具",
                        ToolRiskLevel.HIGH, "脱敏摘要", "a".repeat(64), ToolApprovalPolicy.EACH_CALL, 1, null)));
    }
}
