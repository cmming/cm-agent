package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AgentRunRequest(
        UUID runId,
        UUID tenantId,
        AgentDefinition agent,
        ModelConfig modelConfig,
        PrincipalRef principal,
        String input,
        List<ToolDefinition> tools
) {

    public AgentRunRequest {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agent, "agent 不能为空");
        Objects.requireNonNull(modelConfig, "modelConfig 不能为空");
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(tools, "tools 不能为空");
        tools = List.copyOf(tools);
        if (!tenantId.equals(agent.tenantId())) {
            throw new IllegalArgumentException("Agent 不属于当前租户");
        }
        if (!tenantId.equals(modelConfig.tenantId())) {
            throw new IllegalArgumentException("模型配置不属于当前租户");
        }
        if (!tenantId.equals(principal.tenantId())) {
            throw new IllegalArgumentException("调用主体不属于当前租户");
        }
        if (tools.stream().anyMatch(tool -> !tenantId.equals(tool.tenantId()))) {
            throw new IllegalArgumentException("工具不属于当前租户");
        }
    }

    public UUID agentId() {
        return agent.id();
    }
}
