package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.store.InMemoryToolApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolApprovalServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(tenant, "initiator", "发起人", Set.of("agent:run", "agent:approve"));
    private final InMemoryToolApprovalRepository repository = new InMemoryToolApprovalRepository();
    private final RunExecutionService execution = mock(RunExecutionService.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final RuntimeCheckpointRepository checkpoints = mock(RuntimeCheckpointRepository.class);
    private final RunPersistenceService persistence = mock(RunPersistenceService.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final ToolApprovalService service = new ToolApprovalService(repository, runs,
            mock(ConversationMessageRepository.class), checkpoints, persistence, execution,
            (actor, permission) -> actor.permissions().contains(permission) ? AuthorizationDecision.allow() : AuthorizationDecision.deny("权限不足"),
            audit, new SensitiveDataRedactor(), new AgentScopeRuntimeProperties(), null);

    @Test
    void 摘要脱敏且缺少任一权限或非发起人时只读() {
        var view = create();
        assertThat(view.canDecide()).isTrue();
        assertThat(view.items().getFirst().inputSummary()).doesNotContain("private-test-marker", "internal.invalid");
        for (PrincipalRef actor : List.of(
                new PrincipalRef(tenant, "initiator", "发起人", Set.of("agent:approve")),
                new PrincipalRef(tenant, "other", "其他人", Set.of("agent:run", "agent:approve")))) {
            assertThat(service.get(actor, agent, conversation, view.approvalId()).canDecide()).isFalse();
        }
    }

    @Test
    void 跨租户Agent会话查询统一不可见() {
        var view = create();
        assertThatThrownBy(() -> service.get(new PrincipalRef(UUID.randomUUID(), "initiator", "发起人", Set.of()),
                agent, conversation, view.approvalId())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get(principal, UUID.randomUUID(), conversation, view.approvalId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get(principal, agent, UUID.randomUUID(), view.approvalId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void 缺少决定或版本冲突不触发运行恢复() {
        var view = create();
        assertThatThrownBy(() -> service.validateForSubmission(principal, agent, conversation, view.approvalId(), 0, List.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.validateForSubmission(principal, agent, conversation, view.approvalId(), 9,
                List.of(new ToolApprovalService.ItemDecision(view.items().getFirst().itemId(), ToolApprovalDecision.APPROVE))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(execution);
    }

    @Test
    void 待审批会话禁止新消息而其他会话不受影响() {
        create();
        assertThatThrownBy(() -> service.requireNoPending(principal, agent, conversation))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        service.requireNoPending(principal, agent, UUID.randomUUID());
    }

    @Test
    void 过期审批提交收口状态并删除检查点但不执行工具() {
        Instant started = Instant.now().minusSeconds(100);
        UUID approvalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ToolApprovalItem item = new ToolApprovalItem(UUID.randomUUID(), approvalId, "call", UUID.randomUUID(),
                "high_tool", ToolRiskLevel.HIGH, "摘要", "a".repeat(64), ToolApprovalPolicy.EACH_CALL, 1, null);
        repository.save(new ToolApprovalRequest(approvalId, tenant, agent, conversation, runId, "initiator", "发起人",
                ToolApprovalStatus.PENDING, started.plusSeconds(10), 0, runId.toString(), started, started,
                null, null, null, List.of(item)));
        RunRecord waiting = RunRecord.create(runId, tenant, agent, "initiator", "输入", started).waitForApproval();
        when(runs.findByTenantAndAgentAndId(tenant, agent, runId)).thenReturn(java.util.Optional.of(waiting));

        assertThatThrownBy(() -> service.validateForSubmission(principal, agent, conversation, approvalId, 0,
                List.of(new ToolApprovalService.ItemDecision(item.id(), ToolApprovalDecision.APPROVE))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(410));

        assertThat(repository.find(tenant, agent, conversation, approvalId).orElseThrow().status()).isEqualTo(ToolApprovalStatus.EXPIRED);
        verify(checkpoints).deleteSession(tenant, tenant + ":initiator", runId.toString());
        verify(persistence).complete(eq(principal), eq(waiting), argThat(result -> result.status() == RunStatus.DENIED), eq(List.of()));
        verifyNoInteractions(execution);
    }

    private ToolApprovalService.ToolApprovalView create() {
        return service.create(principal, agent, conversation, UUID.randomUUID(), new RuntimePendingApproval("reply", List.of(
                new RuntimePendingApproval.RuntimePendingToolCall("call", UUID.randomUUID(), "high_tool", ToolRiskLevel.HIGH,
                        "apiKey=private-test-marker http://internal.invalid/path", "a".repeat(64)))));
    }

    @Test
    void 历史查询保留多轮决定及脱敏摘要但不授予再次审批能力() {
        var first = create();
        var second = create();
        var pending = create();
        for (var approval : List.of(first, second)) {
            repository.decide(tenant, agent, conversation, approval.approvalId(), 0, ToolApprovalStatus.APPROVED,
                    java.util.Map.of(approval.items().getFirst().itemId(), ToolApprovalDecision.APPROVE),
                    "initiator", "审批人", Instant.now());
        }
        var history = service.listHistory(principal, agent, conversation, new ToolApprovalHistoryPageRequest(20, null, null));
        assertThat(history).hasSize(2).allSatisfy(view -> {
            assertThat(view.canDecide()).isFalse();
            assertThat(view.items().getFirst().decision()).isEqualTo(ToolApprovalDecision.APPROVE);
            assertThat(view.items().getFirst().inputSummary()).doesNotContain("private-test-marker", "internal.invalid");
        });
        assertThat(history).extracting(ToolApprovalService.ToolApprovalView::approvalId).doesNotContain(pending.approvalId());
        verifyNoInteractions(execution, persistence, checkpoints);
    }

    @Test
    void 决定后恢复前置失败也收口Run并清除检查点() {
        var approval = create();
        RunRecord waiting = RunRecord.create(approval.runId(), tenant, agent, "initiator", "输入", Instant.now()).waitForApproval();
        when(runs.findByTenantAndAgentAndId(tenant, agent, approval.runId())).thenReturn(java.util.Optional.of(waiting));
        when(execution.resumePrepared(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Agent 已禁用"));

        assertThatThrownBy(() -> service.decideAndResume(principal, agent, conversation, approval.approvalId(), 0,
                List.of(new ToolApprovalService.ItemDecision(approval.items().getFirst().itemId(), ToolApprovalDecision.APPROVE)),
                ignored -> {}, ignored -> {}, ignored -> {})).isInstanceOf(ResponseStatusException.class);

        verify(persistence).completeFailure(principal, waiting);
        verify(checkpoints).deleteSession(tenant, tenant + ":initiator", approval.runId().toString());
        verify(audit).append(tenant, "initiator", "TOOL_APPROVAL_RESUME", "TOOL_APPROVAL", approval.approvalId().toString(),
                "FAILED", "审批已接受但运行恢复失败");
    }
}
