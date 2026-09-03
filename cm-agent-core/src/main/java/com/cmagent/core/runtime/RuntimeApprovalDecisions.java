package com.cmagent.core.runtime;

import com.cmagent.core.domain.ToolApprovalDecision;

import java.util.List;

/**
 * Runtime 恢复时使用的全量审批决定。
 *
 * @param items 必须覆盖当前 ASK 中全部工具调用的决定
 */
public record RuntimeApprovalDecisions(List<Item> items) {
    public RuntimeApprovalDecisions {
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("审批决定不能为空");
        }
    }

    /**
     * @param toolCallId AgentScope 调用标识
     * @param toolId 创建审批时的工具标识，防止同名工具被替换后借用旧决定
     * @param inputHash 创建审批时对规范化工具输入计算的 SHA-256 哈希
     * @param decision 本次具体调用的允许或拒绝决定
     */
    public record Item(String toolCallId, java.util.UUID toolId, String inputHash, ToolApprovalDecision decision) {
        public Item {
            java.util.Objects.requireNonNull(toolId, "toolId 不能为空");
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId 不能为空");
            }
            if (inputHash == null || !inputHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("inputHash 必须是小写 SHA-256 十六进制值");
            }
            java.util.Objects.requireNonNull(decision, "decision 不能为空");
        }
    }
}
