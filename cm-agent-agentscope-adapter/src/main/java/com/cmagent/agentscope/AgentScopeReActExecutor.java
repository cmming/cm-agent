package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 基于 AgentScope ReActAgent 执行单轮运行并治理工具调用生命周期。 */
final class AgentScopeReActExecutor implements AgentScopeExecutor {

    private static final String TIMEOUT_MESSAGE = "Agent 运行超时";
    private static final String FAILURE_MESSAGE = "Agent 运行失败";
    private static final String MODEL_TIMEOUT_PREFIX = "Model request timeout after ";

    private final AgentScopeRuntimeOptions options;
    private final AgentScopeModelFactory modelFactory;
    private final AgentLifecycle lifecycle;

    /** 使用默认 AgentScope 生命周期实现创建执行器。 */
    AgentScopeReActExecutor(AgentScopeRuntimeOptions options, AgentScopeModelFactory modelFactory) {
        this(options, modelFactory, new AgentLifecycle() {
            /** 使用 AgentScope 上下文中断当前 Agent。 */
            @Override
            public void interrupt(ReActAgent agent, RuntimeContext context) {
                agent.interrupt(context);
            }

            /** 关闭当前 Agent 并释放 AgentScope 资源。 */
            @Override
            public void close(ReActAgent agent) {
                agent.close();
            }
        });
    }

    /** 使用指定生命周期实现创建执行器，便于隔离和测试 AgentScope 生命周期操作。 */
    AgentScopeReActExecutor(
            AgentScopeRuntimeOptions options,
            AgentScopeModelFactory modelFactory,
            AgentLifecycle lifecycle
    ) {
        this.options = Objects.requireNonNull(options, "options 不能为空");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
    }

    /** 执行 AgentScope ReAct 流程并归集模型、工具和中止状态。 */
    @Override
    public AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway
    ) {
        Objects.requireNonNull(spec, "spec 不能为空");
        Objects.requireNonNull(credential, "credential 不能为空");
        Objects.requireNonNull(toolGateway, "toolGateway 不能为空");

        List<AgentScopeToolBridge> bridges = new ArrayList<>();
        AgentScopeRunGate runGate = new AgentScopeRunGate(options.toolTimeout());
        ReActAgent agent = null;
        RuntimeContext context = null;
        RuntimeException primaryFailure = null;
        try {
            Toolkit toolkit = new Toolkit();
            ObjectMapper objectMapper = new ObjectMapper();
            spec.request().tools().forEach(tool -> {
                AgentScopeToolBridge bridge =
                        new AgentScopeToolBridge(
                                spec.request(), tool, toolGateway, objectMapper, runGate);
                bridges.add(bridge);
                toolkit.registerAgentTool(bridge);
            });

            Model model = modelFactory.create(
                    spec.request().modelConfig(), spec.request().agent(), credential);
            ExecutionConfig modelConfig = ExecutionConfig.builder()
                    .timeout(options.modelTimeout())
                    .maxAttempts(options.modelMaxAttempts())
                    .build();
            ExecutionConfig toolConfig = ExecutionConfig.builder()
                    .timeout(options.toolTimeout())
                    .maxAttempts(1)
                    .build();
            context = RuntimeContext.builder()
                    .userId(spec.tenantId() + ":" + spec.principalId())
                    .sessionId(spec.runId().toString())
                    .put("tenantId", spec.tenantId().toString())
                    .put("agentId", spec.agentId().toString())
                    .put("principalId", spec.principalId())
                    .put("runId", spec.runId().toString())
                    .build();
            agent = ReActAgent.builder()
                    .name(spec.request().agent().name())
                    .sysPrompt(spec.request().agent().systemPrompt())
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(spec.request().agent().maxIterations())
                    .modelExecutionConfig(modelConfig)
                    .toolExecutionConfig(toolConfig)
                    .enableMetaTool(false)
                    .enableTaskList(false)
                    .build();
            lifecycle.onCreated(agent, context);

            AtomicReference<Msg> finalMessage = new AtomicReference<>();
            ReActAgent activeAgent = agent;
            RuntimeContext activeContext = context;
            agent.streamEvents(new UserMessage(spec.userInput()), context)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultTextDeltaEvent toolResultEvent) {
                            runGate.observeToolResultText(
                                    toolResultEvent.getToolCallId(), toolResultEvent.getDelta());
                        }
                        if (event instanceof ToolResultEndEvent toolResultEndEvent) {
                            boolean bridgeCompleted = bridges.stream().anyMatch(bridge ->
                                    bridge.hasCompletedToolCall(toolResultEndEvent.getToolCallId()));
                            runGate.observeToolResultEnd(
                                    toolResultEndEvent.getToolCallId(),
                                    bridgeCompleted);
                        }
                        if (event instanceof AgentResultEvent resultEvent) {
                            finalMessage.set(resultEvent.getResult());
                        }
                        throwIfRunAborted(
                                runGate, activeAgent, activeContext, lifecycle);
                    })
                    .blockLast();

            throwIfRunAborted(runGate, agent, context, lifecycle);
            List<ToolCallRecord> records = collectRecords(bridges);
            return completedResult(finalMessage.get(), records);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            try {
                throwIfInfrastructureFailure(runGate, agent, context, lifecycle);
            } catch (RuntimeException infrastructureFailure) {
                primaryFailure = infrastructureFailure;
                throw infrastructureFailure;
            }
            List<ToolCallRecord> records = collectRecords(bridges);
            boolean timedOut = runGate.isToolTimedOut() || isTimeoutFailure(exception);
            RuntimeException recordedInterruptFailure = runGate.interruptFailure();
            if (recordedInterruptFailure != null) {
                primaryFailure = recordedInterruptFailure;
                throw recordedInterruptFailure;
            }
            if (timedOut && agent != null && context != null) {
                try {
                    interruptOnce(runGate, agent, context, lifecycle);
                } catch (RuntimeException interruptFailure) {
                    primaryFailure = interruptFailure;
                    throw interruptFailure;
                }
            }
            ToolCallRecord denied = findDenied(records);
            if (denied != null) {
                primaryFailure = null;
                return AgentScopeExecutionResult.denied(denied.errorMessage(), records);
            }
            if (timedOut) {
                primaryFailure = null;
                return AgentScopeExecutionResult.failed(TIMEOUT_MESSAGE, records);
            }
            if (isProviderFailure(exception)) {
                primaryFailure = null;
                return AgentScopeExecutionResult.failed(FAILURE_MESSAGE, records);
            }
            throw exception;
        } finally {
            if (agent != null) {
                try {
                    lifecycle.close(agent);
                } catch (RuntimeException closeFailure) {
                    if (primaryFailure == null || primaryFailure == closeFailure) {
                        throw closeFailure;
                    }
                    primaryFailure.addSuppressed(closeFailure);
                }
            }
        }
    }

    /** 检查工具基础设施失败，并在必要时中断 Agent。 */
    private static void throwIfInfrastructureFailure(
            AgentScopeRunGate runGate,
            ReActAgent agent,
            RuntimeContext context,
            AgentLifecycle lifecycle
    ) {
        try {
            runGate.throwIfInfrastructureFailure();
        } catch (ToolInvocationInfrastructureException failure) {
            if (agent != null && context != null) {
                try {
                    runGate.interruptOnce(() -> lifecycle.interrupt(agent, context));
                } catch (RuntimeException interruptFailure) {
                    if (interruptFailure != failure) {
                        failure.addSuppressed(interruptFailure);
                    }
                }
            }
            throw failure;
        }
    }

    /** 检查运行是否因工具超时或基础设施失败而应中止。 */
    private static void throwIfRunAborted(
            AgentScopeRunGate runGate,
            ReActAgent agent,
            RuntimeContext context,
            AgentLifecycle lifecycle
    ) {
        throwIfInfrastructureFailure(runGate, agent, context, lifecycle);
        if (runGate.isToolTimedOut()) {
            interruptOnce(runGate, agent, context, lifecycle);
            throw new ToolTimeoutSignal();
        }
    }

    /** 通过运行门控确保 Agent 只被中断一次。 */
    private static void interruptOnce(
            AgentScopeRunGate runGate,
            ReActAgent agent,
            RuntimeContext context,
            AgentLifecycle lifecycle
    ) {
        runGate.interruptOnce(() -> lifecycle.interrupt(agent, context));
    }

    /** 收集所有工具桥接器产生的调用记录。 */
    private static List<ToolCallRecord> collectRecords(List<AgentScopeToolBridge> bridges) {
        return bridges.stream()
                .flatMap(bridge -> bridge.records().stream())
                .toList();
    }

    /** 查找首条被授权策略拒绝的工具调用记录。 */
    private static ToolCallRecord findDenied(List<ToolCallRecord> records) {
        return records.stream()
                .filter(record -> record.status() == RunStatus.DENIED)
                .findFirst()
                .orElse(null);
    }

    /** 将 AgentScope 最终消息和工具记录转换为领域执行结果。 */
    static AgentScopeExecutionResult completedResult(Msg result, List<ToolCallRecord> records) {
        ToolCallRecord denied = findDenied(records);
        if (denied != null) {
            String output = result == null ? "" : result.getTextContent();
            return AgentScopeExecutionResult.denied(output, denied.errorMessage(), records);
        }
        if (result == null) {
            return AgentScopeExecutionResult.failed(FAILURE_MESSAGE, records);
        }
        return AgentScopeExecutionResult.succeeded(result.getTextContent(), records);
    }

    /** 判断异常链是否表示模型或执行流程超时。 */
    private static boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof ModelException
                    && current.getMessage() != null
                    && current.getMessage().startsWith(MODEL_TIMEOUT_PREFIX)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 判断异常链是否来自模型 Provider 或其 HTTP 传输层。 */
    private static boolean isProviderFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ModelException
                    || current instanceof HttpTransportException
                    || current instanceof ModelHttpException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 抽象 Agent 创建后的中断和关闭动作。 */
    interface AgentLifecycle {

        /** 在 Agent 创建完成后执行生命周期初始化钩子。 */
        default void onCreated(ReActAgent agent, RuntimeContext context) {
        }

        /** 中断指定 Agent 的当前运行。 */
        void interrupt(ReActAgent agent, RuntimeContext context);

        /** 释放指定 Agent 占用的资源。 */
        void close(ReActAgent agent);
    }

    /** 用于从事件流中标记工具超时的内部控制信号。 */
    private static final class ToolTimeoutSignal extends RuntimeException {
    }
}
