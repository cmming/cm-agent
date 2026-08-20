package com.cmagent.agentscope;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 隔离领域运行适配与具体 AgentScope 执行方式的包内策略接口。
 *
 * <p>{@link AgentScopeRuntimeAdapter} 只依赖此接口，因此单元测试可以使用不访问模型 Provider 的执行器；
 * 生产装配则使用 {@link AgentScopeReActExecutor}。三参数方法保持为唯一抽象方法，兼容现有 Lambda 和
 * 不支持增量事件的自定义实现。</p>
 */
@FunctionalInterface
interface AgentScopeExecutor {

    /**
     * 使用受控凭据和工具治理入口同步执行一次 AgentScope 运行。
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @return 已归并模型输出与工具记录的终态结果
     */
    AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway);

    /**
     * 执行 AgentScope 运行，并在底层模型提供文本片段时通知调用方。
     *
     * <p>默认实现有意忽略增量消费者并回退到三参数执行路径。真实执行器覆盖此方法转发事件；
     * 测试执行器和已有自定义实现无需为了流式能力改变原有函数式接口。</p>
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param outputDeltaConsumer 接收模型文本增量的非空消费者
     * @return 已归并模型输出与工具记录的终态结果
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
