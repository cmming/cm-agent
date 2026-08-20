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
 * 将 CM Agent 运行时请求适配为 AgentScope 执行流程。
 */
public class AgentScopeRuntimeAdapter implements AgentRuntime {

    private final ModelCredentialProvider credentialProvider;
    private final ToolInvocationGateway toolGateway;
    private final AgentScopeExecutor executor;
    private final Clock clock;

    /**
     * 创建运行时适配器实例。
      *
      * @param credentialProvider 按租户和模型配置解析凭据的组件
      * @param toolGateway 受治理的工具调用网关
      * @param executor AgentScope 或异步任务执行器
      * @param clock 提供可测试时间的时钟
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
     * 根据运行时选项创建使用默认执行器的适配器。
      *
      * @param credentialProvider 按租户和模型配置解析凭据的组件
      * @param toolGateway 受治理的工具调用网关
      * @param options AgentScope 运行选项
      * @param clock 提供可测试时间的时钟
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
     * 将领域运行请求包装为 AgentScope 执行规格。
      *
      * @param request 当前运行或工具调用请求
     */
    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
        return new AgentScopeRunSpec(request);
    }

    /**
     * 执行一次运行请求，并将凭据异常转换为失败结果。
      *
      * @param request 当前运行或工具调用请求
     */
    @Override
    public AgentRunResult run(AgentRunRequest request) {
        return run(request, ignored -> {
        });
    }

    @Override
    /**
     * 执行一次运行，并将 AgentScope 的模型文本增量传递给上层。
     *
     * <p>凭据不可用仍按普通运行路径转换为受控失败，避免流式连接暴露底层凭据或异常详情。</p>
     *
     * @param request 当前运行请求
     * @param outputDeltaConsumer 接收模型输出片段的消费者
     * @return 运行完成后的终态结果
     */
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
