package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.List;
import java.util.UUID;

public record AgentRunRequest(
        UUID tenantId,
        UUID agentId,
        AgentDefinition agent,
        PrincipalRef principal,
        String input,
        List<ToolDefinition> tools
) {

    public AgentRunRequest(UUID tenantId, UUID agentId, com.cmagent.api.PrincipalRef principal,
                           String input, List<ToolDefinition> tools) {
        this(tenantId, agentId,
                new AgentDefinition(agentId, tenantId, "未命名 Agent", "", "", null, "", 0, 1, true, List.of(), "", ""),
                principal, input, tools);
    }

    public AgentRunRequest {
        if (!tenantId.equals(agent.tenantId()) || !agentId.equals(agent.id())) {
            throw new IllegalArgumentException("运行请求与 Agent 定义不一致");
        }
        tools = List.copyOf(tools);
    }
}
