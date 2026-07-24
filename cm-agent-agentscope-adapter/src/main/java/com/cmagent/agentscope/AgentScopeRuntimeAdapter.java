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
     */
    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
        return new AgentScopeRunSpec(request);
    }

    /**
     * 执行一次运行请求，并将凭据异常转换为失败结果。
     */
    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Instant startedAt = clock.instant();
        try {
            ModelCredential credential = credentialProvider.resolve(
                    request.tenantId(), request.modelConfig().id());
            AgentScopeExecutionResult execution =
                    executor.execute(toRunSpec(request), credential, toolGateway);
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
