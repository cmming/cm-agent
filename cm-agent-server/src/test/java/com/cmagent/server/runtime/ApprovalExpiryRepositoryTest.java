package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.server.store.InMemoryToolApprovalRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalExpiryRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

    @Test
    void 截止相等及同时间分页跨租户覆盖两种范围() {
        var repository = new InMemoryToolApprovalRepository();
        var first = ApprovalExpiryScannerTest.request(ToolApprovalScope.RUN, UUID.randomUUID(),
                UUID.fromString("10000000-0000-0000-0000-000000000001"), NOW);
        var second = ApprovalExpiryScannerTest.request(ToolApprovalScope.CONVERSATION, UUID.randomUUID(),
                UUID.fromString("f0000000-0000-0000-0000-000000000001"), NOW);
        repository.save(second); repository.save(first);
        repository.save(ApprovalExpiryScannerTest.request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(1)));
        assertThat(repository.findExpiredPending(new ApprovalExpiryPage(NOW.minusNanos(1), null, null, 100))).isEmpty();
        assertThat(repository.findExpiredPending(new ApprovalExpiryPage(NOW, null, null, 1))).containsExactly(first);
        assertThat(repository.findExpiredPending(new ApprovalExpiryPage(NOW, first.expiresAt(), first.id(), 1))).containsExactly(second);
        assertThat(repository.findExpiredPending(new ApprovalExpiryPage(NOW, second.expiresAt(), second.id(), 100))).isEmpty();
        assertThat(repository.expirePending(first, "system:approval-expiry", NOW.minusNanos(1))).isFalse();
        assertThat(repository.expirePending(first, "system:approval-expiry", NOW)).isTrue();
        assertThat(repository.expirePending(first, "system:approval-expiry", NOW)).isFalse();
        assertThat(repository.findByRun(UUID.randomUUID(), first.agentId(), first.runId(), first.id())).isEmpty();
        assertThat(repository.expirePending(ApprovalExpiryScannerTest.request(first.scope(), UUID.randomUUID(), first.id(), NOW), "system", NOW)).isFalse();
        assertThatThrownBy(() -> new ApprovalExpiryPage(NOW, null, UUID.randomUUID(), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 双扫描以及扫描与截止前决定竞争最多一方获胜() throws Exception {
        var repository = new InMemoryToolApprovalRepository();
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (var scope : ToolApprovalScope.values()) {
                for (boolean decision : List.of(false, true)) {
                    var request = ApprovalExpiryScannerTest.request(scope, UUID.randomUUID(), UUID.randomUUID(), NOW);
                    repository.save(request);
                    var start = new CountDownLatch(1);
                    var scan = workers.submit(() -> { start.await(); return repository.expirePending(request, "system", NOW); });
                    var other = workers.submit(() -> {
                        start.await();
                        if (!decision) return repository.expirePending(request, "system", NOW);
                        var decisions = Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE);
                        return scope == ToolApprovalScope.RUN
                                ? repository.decideByRun(request.tenantId(), request.agentId(), request.runId(), request.id(), 0,
                                ToolApprovalStatus.APPROVED, decisions, "initiator", "测试", NOW.minusNanos(1))
                                : repository.decide(request.tenantId(), request.agentId(), request.conversationId(), request.id(), 0,
                                ToolApprovalStatus.APPROVED, decisions, "initiator", "测试", NOW.minusNanos(1));
                    });
                    start.countDown();
                    assertThat(List.of(scan.get(5, TimeUnit.SECONDS), other.get(5, TimeUnit.SECONDS)))
                            .containsExactlyInAnyOrder(true, false);
                }
            }
        }
    }

    @Test
    void 截止时间决定拒绝且资源错配不能过期() {
        var repository = new InMemoryToolApprovalRepository();
        var request = ApprovalExpiryScannerTest.request(ToolApprovalScope.RUN, UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository.save(request);
        assertThat(repository.decideByRun(request.tenantId(), request.agentId(), request.runId(), request.id(), 0,
                ToolApprovalStatus.APPROVED, Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE),
                "initiator", "测试", NOW)).isFalse();
        var wrong = new ToolApprovalRequest(request.id(), request.tenantId(), request.agentId(), request.scope(), null,
                UUID.randomUUID(), request.requestedBy(), request.requestedByDisplayName(), request.status(), request.expiresAt(),
                0, request.checkpointRef(), request.createdAt(), request.updatedAt(), null, null, null, request.items());
        assertThat(repository.expirePending(wrong, "system", NOW)).isFalse();
        assertThat(repository.expirePending(request, "system", NOW)).isTrue();
    }
}
