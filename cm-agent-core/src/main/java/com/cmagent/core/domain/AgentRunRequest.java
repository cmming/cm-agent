package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.List;
import java.util.UUID;

public record AgentRunRequest(
        UUID tenantId,
        UUID agentId,
        PrincipalRef principal,
        String input,
        List<ToolDefinition> tools
) {

    public AgentRunRequest {
        tools = List.copyOf(tools);
    }
}
