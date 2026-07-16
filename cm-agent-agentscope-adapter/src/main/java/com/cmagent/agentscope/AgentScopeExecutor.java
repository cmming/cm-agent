package com.cmagent.agentscope;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;

@FunctionalInterface
interface AgentScopeExecutor {

    AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway);
}
