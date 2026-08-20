package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.core.runtime.ToolInvocationGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * CM Agent 领域运行契约与 AgentScope 执行器之间的边界适配器。
 *
 * <p>一次调用按以下顺序完成：</p>
 * <ol>
 *     <li>使用领域请求中的可信 {@code tenantId + modelConfigId} 解析外部模型凭据；</li>
 *     <li>把请求包装为 {@link AgentScopeRunSpec}，并将所有工具调用限制在受治理的
 *     {@link ToolInvocationGateway} 内；</li>
 *     <li>执行 AgentScope 流程，再补充统一时钟提供的起止时间并映射为领域结果。</li>
 * </ol>
 *
 * <p>本类不持久化凭据，也不接触 Spring Security、Web 或 JDBC 类型，从而保持适配器模块与
 * 服务端安全及持久化实现解耦。</p>
 */
public class AgentScopeRuntimeAdapter implements AgentRuntime {

    private final ModelCredentialProvider credentialProvider;
    private final ToolInvocationGateway toolGateway;
    private final AgentScopeExecutor executor;
    private final Clock clock;

    /**
     * 创建可替换内部执行策略的运行时适配器。
     *
     * <p>构造器保持包内可见，便于合同测试注入不访问外部 Provider 的执行器；生产代码通常通过
     * {@link #create(ModelCredentialProvider, ToolInvocationGateway, AgentScopeRuntimeOptions, Clock)} 创建。</p>
     *
     * @param credentialProvider 按租户和模型配置解析凭据的组件
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param executor AgentScope 执行策略
     * @param clock 提供可测试起止时间的时钟
     */
    AgentScopeRuntimeAdapter(
            ModelCredentialProvider credentialProvider,
            ToolInvocationGateway toolGateway,
            AgentScopeExecutor executor,
            Clock clock
    ) {
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider 不能为空");
        this.toolGateway = Objects.requireNonNull(toolGateway, "toolGateway 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 创建使用 {@link AgentScopeReActExecutor} 的默认运行时适配器。
     *
     * @param credentialProvider 按租户和模型配置解析凭据的组件
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param options 模型超时、工具超时和模型尝试次数配置
     * @param clock 提供可测试起止时间的时钟
     * @return 可直接装配到 Core {@link AgentRuntime} 契约的适配器
     */
    public static AgentScopeRuntimeAdapter create(
            ModelCredentialProvider credentialProvider,
            ToolInvocationGateway toolGateway,
            AgentScopeRuntimeOptions options,
            Clock clock
    ) {
        return new AgentScopeRuntimeAdapter(
                credentialProvider,
                toolGateway,
                new AgentScopeReActExecutor(options, new AgentScopeModelFactory()),
                clock);
    }

    /**
     * 将领域请求包装为不复制安全上下文字段的 AgentScope 执行视图。
     *
     * @param request 当前领域运行请求
     * @return 适配器内部使用的只读运行规格
     */
    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
        return new AgentScopeRunSpec(request);
    }

    /**
     * 以非流式调用方式执行一次运行。
     *
     * <p>内部仍使用同一事件流执行路径，只是不向上层转发文本增量，确保流式与非流式调用具有一致的
     * 工具治理、超时和终态映射语义。</p>
     *
     * @param request 当前领域运行请求
     * @return 包含起止时间和工具记录的领域终态结果
     */
    @Override
    public AgentRunResult run(AgentRunRequest request) {
        return run(request, ignored -> {
        });
    }

    /**
     * 执行一次运行，并将 AgentScope 的模型文本增量传递给上层。
     *
     * <p>文本消费者由 AgentScope 事件处理链同步调用，应保持轻量，并自行满足其下游所需的线程安全约束。
     * 凭据不可用会在进入模型调用前转换为固定失败消息，避免将配置内容、密钥或底层异常暴露给调用方。</p>
     *
     * @param request 当前领域运行请求
     * @param outputDeltaConsumer 接收最终回答文本片段的非空消费者
     * @return 包含起止时间和工具记录的领域终态结果
     */
    @Override
    public AgentRunResult run(AgentRunRequest request, Consumer<String> outputDeltaConsumer) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        Instant startedAt = clock.instant();
        try {
            ModelCredential credential = credentialProvider.resolve(
                    request.tenantId(), request.modelConfig().id());
            AgentScopeExecutionResult execution =
                    executor.execute(toRunSpec(request), credential, toolGateway, outputDeltaConsumer);
            return new AgentRunResult(
                    request.runId(),
                    execution.status(),
                    execution.output(),
                    execution.toolCalls(),
                    startedAt,
                    clock.instant(),
                    execution.errorMessage());
        } catch (ModelCredentialUnavailableException exception) {
            return new AgentRunResult(
                    request.runId(),
                    RunStatus.FAILED,
                    "",
                    List.of(),
                    startedAt,
                    clock.instant(),
                    "模型凭据不可用");
        }
    }
}
