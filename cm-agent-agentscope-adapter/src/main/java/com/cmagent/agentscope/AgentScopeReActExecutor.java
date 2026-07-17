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

final class AgentScopeReActExecutor implements AgentScopeExecutor {

    private static final String TIMEOUT_MESSAGE = "Agent 运行超时";
    private static final String FAILURE_MESSAGE = "Agent 运行失败";
    private static final String MODEL_TIMEOUT_PREFIX = "Model request timeout after ";

    private final AgentScopeRuntimeOptions options;
    private final AgentScopeModelFactory modelFactory;
    private final AgentLifecycle lifecycle;

    AgentScopeReActExecutor(AgentScopeRuntimeOptions options, AgentScopeModelFactory modelFactory) {
        this(options, modelFactory, new AgentLifecycle() {
            @Override
            public void interrupt(ReActAgent agent, RuntimeContext context) {
                agent.interrupt(context);
            }

            @Override
            public void close(ReActAgent agent) {
                agent.close();
            }
        });
    }

    AgentScopeReActExecutor(
            AgentScopeRuntimeOptions options,
            AgentScopeModelFactory modelFactory,
            AgentLifecycle lifecycle
    ) {
        this.options = Objects.requireNonNull(options, "options 不能为空");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
    }

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

    private static void interruptOnce(
            AgentScopeRunGate runGate,
            ReActAgent agent,
            RuntimeContext context,
            AgentLifecycle lifecycle
    ) {
        runGate.interruptOnce(() -> lifecycle.interrupt(agent, context));
    }

    private static List<ToolCallRecord> collectRecords(List<AgentScopeToolBridge> bridges) {
        return bridges.stream()
                .flatMap(bridge -> bridge.records().stream())
                .toList();
    }

    private static ToolCallRecord findDenied(List<ToolCallRecord> records) {
        return records.stream()
                .filter(record -> record.status() == RunStatus.DENIED)
                .findFirst()
                .orElse(null);
    }

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

    interface AgentLifecycle {

        default void onCreated(ReActAgent agent, RuntimeContext context) {
        }

        void interrupt(ReActAgent agent, RuntimeContext context);

        void close(ReActAgent agent);
    }

    private static final class ToolTimeoutSignal extends RuntimeException {
    }
}
