package com.cmagent.core.tool;

import com.cmagent.api.PrincipalRef;

import java.util.Objects;
import java.util.UUID;

/**
 * 封装工具执行所需的租户、主体、运行上下文和输入数据。
 */
public record ToolExecutionRequest(
        UUID tenantId,
        UUID agentId,
        PrincipalRef principal,
        UUID runId,
        String toolCallId,
        UUID toolId,
        String inputJson,
        ToolInvocationSource source
) {

    /**
     * 校验工具调用来源与租户、主体、Agent 和运行上下文是否一致。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param principal 当前认证主体
      * @param runId 目标运行标识
      * @param toolCallId 工具调用标识
      * @param toolId 目标工具标识
      * @param inputJson 序列化后的工具输入 JSON
      * @param source 待转换的源对象
     */
    public ToolExecutionRequest {
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(inputJson, "inputJson 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        if (source != ToolInvocationSource.LEGACY) {
            if (toolCallId != null && toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId 不能为空");
            }
            if (tenantId == null || principal == null || toolCallId == null) {
                throw new IllegalArgumentException("工具执行上下文必须全部提供或全部省略");
            }
            if (!tenantId.equals(principal.tenantId())) {
                throw new IllegalArgumentException("调用主体不属于当前租户");
            }
            if (source == ToolInvocationSource.AGENT && (agentId == null || runId == null)) {
                throw new IllegalArgumentException("AGENT 调用必须提供 agentId 和 runId");
            }
            if ((source == ToolInvocationSource.DEBUG || source == ToolInvocationSource.MCP)
                    && (agentId != null || runId != null)) {
                throw new IllegalArgumentException(source + " 调用不能绑定 agentId 或 runId");
            }
        }
    }

    /**
     * 创建默认来源为 Agent 运行的完整工具执行请求。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param principal 当前认证主体
      * @param runId 目标运行标识
      * @param toolCallId 工具调用标识
      * @param toolId 目标工具标识
      * @param inputJson 序列化后的工具输入 JSON
     */
    public ToolExecutionRequest(UUID tenantId, UUID agentId, PrincipalRef principal, UUID runId,
                                String toolCallId, UUID toolId, String inputJson) {
        this(tenantId, agentId, principal, runId, toolCallId, toolId, inputJson,
                ToolInvocationSource.AGENT);
    }

    /**
     * 创建不带运行上下文的兼容工具执行请求。
      *
      * @param toolId 目标工具标识
      * @param inputJson 序列化后的工具输入 JSON
     */
    public ToolExecutionRequest(UUID toolId, String inputJson) {
        this(null, null, null, null, null, toolId, inputJson, ToolInvocationSource.LEGACY);
    }

    /**
     * 判断请求是否包含 Agent 工具调用所需的完整运行上下文。
     *
     * @return 租户、Agent、主体、运行和工具调用标识均有效时返回 {@code true}
     */
    public boolean hasRuntimeContext() {
        return source == ToolInvocationSource.AGENT && tenantId != null && agentId != null
                && principal != null && runId != null && toolCallId != null && !toolCallId.isBlank();
    }
}
