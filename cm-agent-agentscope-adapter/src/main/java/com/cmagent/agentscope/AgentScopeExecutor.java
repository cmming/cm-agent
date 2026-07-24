package com.cmagent.agentscope;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;

/**
 * 执行 AgentScope 运行请求的内部策略接口。
 */
@FunctionalInterface
interface AgentScopeExecutor {

    /**
     * 使用模型凭据和工具网关执行一次 AgentScope 运行。
     */
    AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway);
}
