package com.cmagent.server.store;

import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalItem;
import com.cmagent.core.domain.ToolApprovalPolicy;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryToolApprovalRepositoryTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Test
    void 历史按决定时间及字符串ID翻页且隔离所有资源边界() {
        var repository = new InMemoryToolApprovalRepository();
        var pending = request();
        repository.save(pending);
        var lower = historyRequest("10000000-0000-0000-0000-000000000001", ToolApprovalStatus.APPROVED);
        var higher = historyRequest("f0000000-0000-0000-0000-000000000001", ToolApprovalStatus.DENIED);
        repository.save(lower);
        repository.save(higher);
        var first = new ToolApprovalHistoryPageRequest(1, null, null);
        assertThat(repository.listHistory(TENANT_ID, AGENT_ID, CONVERSATION_ID, first)).containsExactly(higher);
        var next = new ToolApprovalHistoryPageRequest(20, higher.decidedAt(), higher.id());
        assertThat(repository.listHistory(TENANT_ID, AGENT_ID, CONVERSATION_ID, next)).containsExactly(lower);
        assertThat(repository.listHistory(UUID.randomUUID(), AGENT_ID, CONVERSATION_ID, first)).isEmpty();
        assertThat(repository.listHistory(TENANT_ID, UUID.randomUUID(), CONVERSATION_ID, first)).isEmpty();
        assertThat(repository.listHistory(TENANT_ID, AGENT_ID, UUID.randomUUID(), first)).isEmpty();
        assertThat(repository.find(TENANT_ID, AGENT_ID, CONVERSATION_ID, pending.id()).orElseThrow().status()).isEqualTo(ToolApprovalStatus.PENDING);
    }

    private static ToolApprovalRequest historyRequest(String id, ToolApprovalStatus status) {
        var base = request();
        UUID approvalId = UUID.fromString(id);
        var item = base.items().getFirst();
        var decision = status == ToolApprovalStatus.APPROVED ? ToolApprovalDecision.APPROVE : ToolApprovalDecision.DENY;
        return new ToolApprovalRequest(approvalId, TENANT_ID, AGENT_ID, CONVERSATION_ID, base.runId(),
                base.requestedBy(), base.requestedByDisplayName(), status, base.expiresAt(), 1, base.checkpointRef(),
                base.createdAt(), base.updatedAt(), "principal", "审批人", base.createdAt().plusSeconds(1),
                List.of(new ToolApprovalItem(item.id(), approvalId, item.toolCallId(), item.toolId(), item.toolName(),
                        item.riskLevel(), item.inputSummary(), item.inputHash(), item.policy(), item.policyVersion(), decision)));
    }

    @Test
    void 决定必须在租户资源边界和版本一致时原子生效() {
        InMemoryToolApprovalRepository repository = new InMemoryToolApprovalRepository();
        ToolApprovalRequest request = request();
        repository.save(request);

        assertThat(repository.decide(
                UUID.randomUUID(), AGENT_ID, CONVERSATION_ID, request.id(), 0,
                ToolApprovalStatus.APPROVED, Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "principal", "审批人", Instant.now())).isFalse();
        assertThat(repository.decide(
                TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id(), 0,
                ToolApprovalStatus.APPROVED, Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "principal", "审批人", Instant.now())).isTrue();
        assertThat(repository.decide(
                TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id(), 0,
                ToolApprovalStatus.APPROVED, Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "principal", "审批人", Instant.now())).isFalse();

        ToolApprovalRequest decided = repository.find(TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id()).orElseThrow();
        assertThat(decided.status()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(decided.version()).isEqualTo(1);
        assertThat(decided.items().getFirst().decision()).isEqualTo(ToolApprovalDecision.APPROVE);
    }

    private static ToolApprovalRequest request() {
        UUID approvalId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        ToolApprovalItem item = new ToolApprovalItem(
                itemId, approvalId, "call-1", UUID.randomUUID(), "dangerous_tool", ToolRiskLevel.HIGH,
                "{\"path\":\"[REDACTED]\"}", "0".repeat(64), ToolApprovalPolicy.EACH_CALL, 1, null);
        return new ToolApprovalRequest(
                approvalId, TENANT_ID, AGENT_ID, CONVERSATION_ID, UUID.randomUUID(),
                "principal", "发起人", ToolApprovalStatus.PENDING, now.plusSeconds(900), 0,
                "checkpoint", now, now, null, null, null, List.of(item));
    }

    @Test
    void 批准与拒绝并发竞争只接受一个决定() {
        var repository = new InMemoryToolApprovalRepository();
        var request = request();
        repository.save(request);
        var approve = java.util.concurrent.CompletableFuture.supplyAsync(() -> repository.decide(
                TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id(), 0, ToolApprovalStatus.APPROVED,
                Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "principal", "审批人", request.createdAt().plusSeconds(1)));
        var deny = java.util.concurrent.CompletableFuture.supplyAsync(() -> repository.decide(
                TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id(), 0, ToolApprovalStatus.DENIED,
                Map.of(request.items().getFirst().id(), ToolApprovalDecision.DENY),
                "principal", "审批人", request.createdAt().plusSeconds(1)));
        assertThat(List.of(approve.join(), deny.join())).containsExactlyInAnyOrder(true, false);
        assertThat(repository.find(TENANT_ID, AGENT_ID, CONVERSATION_ID, request.id()).orElseThrow().version()).isEqualTo(1);
    }
}
