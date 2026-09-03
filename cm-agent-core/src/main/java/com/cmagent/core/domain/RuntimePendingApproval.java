package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Runtime 暂停后返回给服务编排层的限长审批快照，持久化与对外展示前必须由服务端脱敏。
 *
 * @param replyId 触发 ASK 的 AgentScope 回复标识
 * @param items 不包含原始输入的待审批明细
 */
public record RuntimePendingApproval(String replyId, List<RuntimePendingToolCall> items) {
    public RuntimePendingApproval {
        if (replyId == null || replyId.isBlank()) {
            throw new IllegalArgumentException("replyId 不能为空");
        }
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("待审批工具不能为空");
        }
    }

    /**
     * @param toolCallId AgentScope 调用标识
     * @param toolId CM Agent 工具标识
     * @param toolName 工具名称快照
     * @param riskLevel 风险等级快照
     * @param inputSummary 已限长、尚待服务端脱敏的展示摘要，不包含原始输入 Map
     * @param inputHash 规范化输入哈希，仅供服务端绑定调用
     */
    public record RuntimePendingToolCall(
            String toolCallId,
            java.util.UUID toolId,
            String toolName,
            ToolRiskLevel riskLevel,
            String inputSummary,
            String inputHash
    ) {
        public RuntimePendingToolCall {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId 不能为空");
            }
            Objects.requireNonNull(toolId, "toolId 不能为空");
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName 不能为空");
            }
            Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
            inputSummary = inputSummary == null ? "" : inputSummary;
            if (inputHash == null || inputHash.isBlank()) {
                throw new IllegalArgumentException("inputHash 不能为空");
            }
        }
    }
}
