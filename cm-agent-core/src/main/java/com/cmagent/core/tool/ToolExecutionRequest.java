package com.cmagent.core.tool;

import com.cmagent.api.PrincipalRef;

import java.util.Objects;
import java.util.UUID;

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

    public ToolExecutionRequest(UUID tenantId, UUID agentId, PrincipalRef principal, UUID runId,
                                String toolCallId, UUID toolId, String inputJson) {
        this(tenantId, agentId, principal, runId, toolCallId, toolId, inputJson,
                ToolInvocationSource.AGENT);
    }

    public ToolExecutionRequest(UUID toolId, String inputJson) {
        this(null, null, null, null, null, toolId, inputJson, ToolInvocationSource.LEGACY);
    }

    public boolean hasRuntimeContext() {
        return source == ToolInvocationSource.AGENT && tenantId != null && agentId != null
                && principal != null && runId != null && toolCallId != null && !toolCallId.isBlank();
    }
}
