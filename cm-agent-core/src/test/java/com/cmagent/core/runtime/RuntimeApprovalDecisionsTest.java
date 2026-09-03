package com.cmagent.core.runtime;

import com.cmagent.core.domain.ToolApprovalDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeApprovalDecisionsTest {
    private static final String INPUT_HASH = "a".repeat(64);

    @Test
    void 接受绑定调用标识和输入哈希的完整决定() {
        new RuntimeApprovalDecisions(List.of(
                new RuntimeApprovalDecisions.Item("call-1", java.util.UUID.randomUUID(), INPUT_HASH, ToolApprovalDecision.APPROVE)));
    }

    @Test
    void 拒绝空决定集合和非法输入哈希() {
        assertThatThrownBy(() -> new RuntimeApprovalDecisions(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("审批决定不能为空");
        assertThatThrownBy(() -> new RuntimeApprovalDecisions.Item(
                "call-1", java.util.UUID.randomUUID(), "ABC", ToolApprovalDecision.DENY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inputHash 必须是小写 SHA-256 十六进制值");
    }
}
