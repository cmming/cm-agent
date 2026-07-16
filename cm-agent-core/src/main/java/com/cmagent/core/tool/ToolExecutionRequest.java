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
        String inputJson
) {

    public ToolExecutionRequest {
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(inputJson, "inputJson 不能为空");
        boolean hasAnyRuntimeContext = tenantId != null || agentId != null || principal != null
                || runId != null || toolCallId != null;
        boolean hasCompleteRuntimeContext = tenantId != null && agentId != null && principal != null
                && runId != null && toolCallId != null;
        if (hasAnyRuntimeContext && !hasCompleteRuntimeContext) {
            throw new IllegalArgumentException("工具执行上下文必须全部提供或全部省略");
        }
        if (hasCompleteRuntimeContext && toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        if (hasCompleteRuntimeContext && !tenantId.equals(principal.tenantId())) {
            throw new IllegalArgumentException("调用主体不属于当前租户");
        }
    }

    public ToolExecutionRequest(UUID toolId, String inputJson) {
        this(null, null, null, null, null, toolId, inputJson);
    }

    public boolean hasRuntimeContext() {
        return tenantId != null && agentId != null && principal != null && runId != null
                && toolCallId != null && !toolCallId.isBlank();
    }
}
