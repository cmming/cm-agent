package com.cmagent.core.runtime;

import com.cmagent.api.PrincipalRef;

import java.util.Objects;
import java.util.UUID;

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
