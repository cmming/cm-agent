package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 保存一次 ASK 事件中的单个待审批工具调用。
 *
 * @param id 审批明细公开标识
 * @param approvalId 所属审批请求标识
 * @param toolCallId AgentScope 工具调用标识
 * @param toolId CM Agent 工具标识
 * @param toolName 工具名称快照
 * @param riskLevel 创建审批时的风险等级快照
 * @param inputSummary 已脱敏、限长且只允许纯文本展示的输入摘要
 * @param inputHash 规范化原始输入哈希，用于绑定本次具体调用，不对前端暴露
 * @param policy 创建审批时命中的策略
 * @param policyVersion 策略版本快照
 * @param decision 单项决定；PENDING 请求中为 {@code null}
 */
public record ToolApprovalItem(
        UUID id,
        UUID approvalId,
        String toolCallId,
        UUID toolId,
        String toolName,
        ToolRiskLevel riskLevel,
        String inputSummary,
        String inputHash,
        ToolApprovalPolicy policy,
        long policyVersion,
        ToolApprovalDecision decision
) {
    public ToolApprovalItem {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(approvalId, "approvalId 不能为空");
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
        Objects.requireNonNull(policy, "policy 不能为空");
        if (policyVersion < 0) {
            throw new IllegalArgumentException("policyVersion 不能为负数");
        }
    }

    /** @return 写入决定后的不可变明细 */
    public ToolApprovalItem decide(ToolApprovalDecision value) {
        if (decision != null) {
            throw new IllegalStateException("审批明细已处理");
        }
        return new ToolApprovalItem(id, approvalId, toolCallId, toolId, toolName, riskLevel,
                inputSummary, inputHash, policy, policyVersion, Objects.requireNonNull(value, "decision 不能为空"));
    }
}
