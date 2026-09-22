package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolApprovalDomainTest {

    @Test
    void 会话审批必须携带会话而运行审批不得携带会话() {
        UUID approvalId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        ToolApprovalRequest conversationRequest = request(
                approvalId, ToolApprovalScope.CONVERSATION, conversationId);
        ToolApprovalRequest runRequest = request(
                UUID.randomUUID(), ToolApprovalScope.RUN, null);

        assertThat(conversationRequest.conversationId()).isEqualTo(conversationId);
        assertThat(runRequest.conversationId()).isNull();
        assertThatThrownBy(() -> request(UUID.randomUUID(), ToolApprovalScope.CONVERSATION, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("会话审批的 conversationId 不能为空");
        assertThatThrownBy(() -> request(UUID.randomUUID(), ToolApprovalScope.RUN, conversationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("运行审批不能携带 conversationId");
    }

    private static ToolApprovalRequest request(
            UUID approvalId,
            ToolApprovalScope scope,
            UUID conversationId
    ) {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        ToolApprovalItem item = new ToolApprovalItem(
                UUID.randomUUID(), approvalId, "call-1", UUID.randomUUID(), "echo",
                ToolRiskLevel.HIGH, "{}", "hash", ToolApprovalPolicy.EACH_CALL, 0, null);
        return new ToolApprovalRequest(
                approvalId, UUID.randomUUID(), UUID.randomUUID(), scope, conversationId,
                UUID.randomUUID(), "tester", "测试人", ToolApprovalStatus.PENDING,
                now.plusSeconds(60), 0, "checkpoint", now, now, null, null, null, List.of(item));
    }
}
