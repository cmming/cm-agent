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
      *
      * @param spec AgentScope 运行规格
      * @param credential 调用模型所需的受控凭据
      * @param toolGateway 受治理的工具调用网关
     */
    AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway);
}
