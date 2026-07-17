package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;

import java.util.Objects;
import java.util.UUID;

public record AgentScopeRunSpec(AgentRunRequest request) {

    public AgentScopeRunSpec {
        Objects.requireNonNull(request, "request 不能为空");
    }

    public UUID runId() {
        return request.runId();
    }

    public UUID tenantId() {
        return request.tenantId();
    }

    public UUID agentId() {
        return request.agent().id();
    }

    public String principalId() {
        return request.principal().principalId();
    }

    public String userInput() {
        return request.input();
    }
}
