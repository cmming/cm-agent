package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.AgentRuntime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AgentScopeRuntimeAdapter implements AgentRuntime {

    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
        return new AgentScopeRunSpec(
                request.tenantId().toString(),
                request.agentId().toString(),
                request.principal().principalId(),
                request.input()
        );
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        AgentScopeRunSpec spec = toRunSpec(request);
        Instant now = Instant.now();
        return new AgentRunResult(
                UUID.randomUUID(),
                RunStatus.FAILED,
                "",
                List.of(),
                now,
                now,
                "AgentScope 真实运行桥接尚未启用，已生成受控运行规格 " + spec.agentId()
        );
    }
}
