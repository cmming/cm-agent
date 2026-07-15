package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.AgentRuntime;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.agent.RuntimeContext;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.UUID;

public class AgentScopeRuntimeAdapter implements AgentRuntime {
    private final Function<com.cmagent.core.domain.AgentDefinition, Model> modelFactory;
    private final Duration timeout;

    public AgentScopeRuntimeAdapter() {
        this(new AgentScopeModelFactory(), Duration.ofSeconds(60));
    }

    public AgentScopeRuntimeAdapter(Function<com.cmagent.core.domain.AgentDefinition, Model> modelFactory, Duration timeout) {
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
    }

    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
        return new AgentScopeRunSpec(
                request.tenantId().toString(),
                request.agentId().toString(),
                request.principal().principalId(),
                request.input(),
                request.agent().systemPrompt(), request.agent().modelName(), request.agent().temperature(), request.agent().maxIterations()
        );
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Instant started = Instant.now();
        try {
            validateTools(request);
            AgentScopeRunSpec spec = toRunSpec(request);
            ReActAgent agent = ReActAgent.builder().name(spec.agentId()).sysPrompt(spec.systemPrompt())
                    .model(modelFactory.apply(request.agent()))
                    .generateOptions(GenerateOptions.builder().modelName(spec.modelName()).temperature(spec.temperature()).stream(false).build())
                    .maxIters(spec.maxIterations()).build();
            Msg response = agent.call(List.of(Msg.builder().name(spec.principalId()).role(MsgRole.USER).textContent(spec.userInput()).build()), (RuntimeContext) null).block(timeout);
            if (response == null) throw new IllegalStateException("AgentScope 未返回最终消息");
            return result(RunStatus.SUCCEEDED, response.getTextContent(), null, started);
        } catch (Throwable ex) {
            return result(RunStatus.FAILED, "", safeMessage(ex), started);
        }
    }

    private void validateTools(AgentRunRequest request) {
        for (var tool : request.tools()) {
            if (!request.tenantId().equals(tool.tenantId())) throw new IllegalArgumentException("工具 tenant 与运行请求不一致");
            if (!tool.enabled()) throw new IllegalArgumentException("工具未启用: " + tool.name());
        }
    }

    private AgentRunResult result(RunStatus status, String output, String error, Instant started) {
        return new AgentRunResult(UUID.randomUUID(), status, output, List.of(), started, Instant.now(), error);
    }

    private static String safeMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
