package com.cmagent.core.runtime;

import com.cmagent.api.PrincipalRef;

import java.util.Objects;
import java.util.UUID;

/**
 * 封装受治理工具调用所需的主体、Agent、运行和输入上下文。
 *
 * @param tenantId 调用归属租户，必须与主体租户一致
 * @param agentId 发起调用的 Agent 标识
 * @param principal 发起调用的认证主体，来自认证上下文
 * @param runId 所属运行标识
 * @param toolCallId 本次工具调用的唯一标识，用于关联运行历史与审计
 * @param toolId 被调用工具的标识
 * @param toolName 模型请求调用的工具名称，须与 toolId 对应定义一致
 * @param inputJson 序列化后的工具输入 JSON
 */
public record ToolInvocationRequest(
        UUID tenantId,
        UUID agentId,
        PrincipalRef principal,
        UUID runId,
        String toolCallId,
        UUID toolId,
        String toolName,
        String inputJson
) {

    /**
     * 校验受治理工具调用的租户、主体和运行上下文一致性。
     *
     * <p>主体必须来自认证上下文且与当前租户一致，防止跨租户工具调用；
     * {@code toolName} 与 {@code toolId} 的对应关系由治理网关在执行前核对,
     * 构造器只负责非空完整性。</p>
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     * @param principal 当前认证主体
     * @param runId 目标运行标识
     * @param toolCallId 工具调用标识
     * @param toolId 目标工具标识
     * @param toolName 模型请求调用的工具名称
     * @param inputJson 序列化后的工具输入 JSON
     */
    public ToolInvocationRequest {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(toolCallId, "toolCallId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(toolName, "toolName 不能为空");
        Objects.requireNonNull(inputJson, "inputJson 不能为空");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (!tenantId.equals(principal.tenantId())) {
            throw new IllegalArgumentException("调用主体不属于当前租户");
        }
    }
}
