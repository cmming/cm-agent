package com.cmagent.core.tool;

import com.cmagent.api.PrincipalRef;

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

    public ToolExecutionRequest(UUID toolId, String inputJson) {
        this(null, null, null, null, null, toolId, inputJson);
    }

    public boolean hasRuntimeContext() {
        return tenantId != null && agentId != null && principal != null && runId != null
                && toolCallId != null && !toolCallId.isBlank();
    }
}
