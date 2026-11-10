package com.cmagent.server.runtime;

import com.cmagent.core.domain.ClientProjectOperation;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalItem;
import com.cmagent.core.domain.ToolApprovalPolicy;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalScope;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientProjectApprovalGuardTest {
    private final UUID tenant = UUID.randomUUID();
    private final String owner = "owner";
    private final ClientProjectOperationRepository operations =
            mock(ClientProjectOperationRepository.class);

    @Test void 脱敏掩盖实际差异时只能拒绝不能批准() {
        var request = request(false);
        var service = service();

        assertThatThrownBy(() -> service.validateClientProjectApprovals(request,
                Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("脱敏预览掩盖");
        assertThatCode(() -> service.validateClientProjectApprovals(request,
                Map.of(request.items().getFirst().id(), ToolApprovalDecision.DENY)))
                .doesNotThrowAnyException();
    }

    @Test void 审批版本和摘要必须匹配权威操作() {
        var request = request(true);
        var service = service();

        assertThatCode(() -> service.validateClientProjectApprovals(request,
                Map.of(request.items().getFirst().id(), ToolApprovalDecision.APPROVE)))
                .doesNotThrowAnyException();
    }

    @Test void 审批版本或原始摘要任一不一致都不能批准() {
        var service = service();
        var versionMismatch = request(true, 2, "a".repeat(64));
        var digestMismatch = request(true, 1, "d".repeat(64));

        assertThatThrownBy(() -> service.validateClientProjectApprovals(versionMismatch,
                Map.of(versionMismatch.items().getFirst().id(), ToolApprovalDecision.APPROVE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("版本、摘要或状态已变化");
        assertThatThrownBy(() -> service.validateClientProjectApprovals(digestMismatch,
                Map.of(digestMismatch.items().getFirst().id(), ToolApprovalDecision.APPROVE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("版本、摘要或状态已变化");
    }

    private ToolApprovalService service() {
        var service = new ToolApprovalService(
                mock(com.cmagent.core.repository.ToolApprovalRepository.class),
                mock(com.cmagent.core.repository.RunRepository.class),
                mock(com.cmagent.core.repository.ConversationMessageRepository.class),
                mock(com.cmagent.core.repository.RuntimeCheckpointRepository.class),
                mock(RunPersistenceService.class), mock(RunExecutionService.class),
                mock(com.cmagent.core.security.PermissionEvaluator.class),
                mock(AuditAppender.class), new SensitiveDataRedactor(),
                new AgentScopeRuntimeProperties(), null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientProjectOperationRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(operations);
        service.configureClientProjectOperations(provider);
        return service;
    }

    private ToolApprovalRequest request(boolean displayExact) {
        return request(displayExact, 1, "a".repeat(64));
    }

    private ToolApprovalRequest request(boolean displayExact, long summaryVersion,
            String summaryDigest) {
        UUID approvalId = UUID.randomUUID(), itemId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        String digest = "a".repeat(64);
        String summary = "项目操作 operationId=" + operationId + " version=" + summaryVersion
                + " digest=" + summaryDigest;
        when(operations.find(tenant, owner, operationId)).thenReturn(Optional.of(
                new ClientProjectOperation(operationId, tenant, owner, UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                        digest, "b".repeat(64), displayExact, "encrypted",
                        ClientProjectOperation.State.PROPOSED, 1, now, now)));
        var item = new ToolApprovalItem(itemId, approvalId, "tool-call", UUID.randomUUID(),
                "client_project_apply_changes", ToolRiskLevel.HIGH, summary, "c".repeat(64),
                ToolApprovalPolicy.EACH_CALL, 1, null);
        return new ToolApprovalRequest(approvalId, tenant, UUID.randomUUID(),
                ToolApprovalScope.CONVERSATION, UUID.randomUUID(), UUID.randomUUID(), owner,
                owner, ToolApprovalStatus.PENDING, now.plusSeconds(300), 1, "checkpoint",
                now, now, null, null, null, List.of(item));
    }
}
