package com.cmagent.agentscope;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;

import java.util.Objects;
import java.util.function.Consumer;

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

    /**
     * 执行 AgentScope 运行，并在底层模型提供文本片段时通知调用方。
     *
     * <p>保留三参数函数式接口可避免测试执行器和第三方适配器发生不兼容；真实执行器覆盖此方法，
     * 而无法提供流式事件的实现仍可使用默认的最终结果路径。</p>
     *
     * @param spec AgentScope 运行规格
     * @param credential 调用模型所需的受控凭据
     * @param toolGateway 受治理的工具调用网关
     * @param outputDeltaConsumer 接收模型文本增量的消费者
     * @return AgentScope 执行结果
     */
    default AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            Consumer<String> outputDeltaConsumer
    ) {
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        return execute(spec, credential, toolGateway);
    }
}
