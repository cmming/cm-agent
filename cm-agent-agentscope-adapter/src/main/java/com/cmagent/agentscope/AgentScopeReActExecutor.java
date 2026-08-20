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
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import java.util.function.Consumer;

/**
 * 基于 AgentScope {@link ReActAgent} 执行单轮请求，并把框架事件收敛为 CM Agent 终态。
 *
 * <p>每次调用都会创建独立的 Model、Toolkit、RuntimeContext 和 ReActAgent，完整流程如下：</p>
 * <ol>
 *     <li>把本次授权可见的工具注册为 {@link AgentScopeToolBridge}；</li>
 *     <li>从可信领域上下文构造 AgentScope 运行身份与会话标识；</li>
 *     <li>订阅 {@link ReActAgent#streamEvents(Msg, RuntimeContext)}，同步收集文本增量、工具终态和最终消息；</li>
 *     <li>按“基础设施失败优先、授权拒绝优先于普通成功”的规则映射领域结果；</li>
 *     <li>无论成功或失败都调用本次 Agent 的 {@link ReActAgent#close()} 生命周期方法。</li>
 * </ol>
 *
 * <p>虽然 AgentScope 暴露响应式事件流，本执行器通过 {@code blockLast()} 保持 Core
 * {@code AgentRuntime} 的同步契约；调用线程会一直等待到事件流结束或失败。</p>
 */
final class AgentScopeReActExecutor implements AgentScopeExecutor {

    private static final String TIMEOUT_MESSAGE = "Agent 运行超时";
    private static final String FAILURE_MESSAGE = "Agent 运行失败";
    private static final String MODEL_TIMEOUT_PREFIX = "Model request timeout after ";

    private final AgentScopeRuntimeOptions options;
    private final AgentScopeModelFactory modelFactory;
    private final AgentLifecycle lifecycle;

    /**
     * 使用真实 AgentScope 中断和关闭操作创建执行器。
     *
     * @param options 模型与工具执行策略
     * @param modelFactory 根据领域配置和受控凭据创建模型的工厂
     */
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

    /**
     * 使用可观察的生命周期协作者创建执行器。
     *
     * <p>生命周期接口仅隔离难以通过本地 Provider Stub 验证的创建后、中断和关闭动作，
     * 不改变生产执行顺序。</p>
     *
     * @param options 模型与工具执行策略
     * @param modelFactory 根据领域配置和受控凭据创建模型的工厂
     * @param lifecycle Agent 创建后、中断和关闭操作的协作者
     */
    AgentScopeReActExecutor(
            AgentScopeRuntimeOptions options,
            AgentScopeModelFactory modelFactory,
            AgentLifecycle lifecycle
    ) {
        this.options = Objects.requireNonNull(options, "options 不能为空");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
    }

    /**
     * 不转发文本增量地执行一次 ReAct 流程。
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @return 已归并模型输出与工具记录的终态结果
     */
    @Override
    public AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway
    ) {
        return execute(spec, credential, toolGateway, ignored -> {
        });
    }

    /**
     * 执行 AgentScope ReAct 流程，并将最终回答的文本块增量暴露给调用方。
     *
     * <p>只转发 {@link TextBlockDeltaEvent}，不会把思考过程、工具参数或工具原始输出送往控制台，
     * 从而保持模型输出流与既有工具治理边界一致。工具、超时和最终结果事件仍仅由本执行器消费。</p>
     *
     * <p>执行为同步阻塞调用。若增量消费者抛出运行时异常，异常会终止事件流并按未知执行异常向上层传播，
     * 因此消费者不应在回调中执行耗时或不受控操作。</p>
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param outputDeltaConsumer 接收最终回答文本片段的非空消费者
     * @return 已归并模型输出与工具记录的终态结果
     */
    @Override
    public AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            Consumer<String> outputDeltaConsumer
    ) {
        Objects.requireNonNull(spec, "spec 不能为空");
        Objects.requireNonNull(credential, "credential 不能为空");
        Objects.requireNonNull(toolGateway, "toolGateway 不能为空");
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");

        List<AgentScopeToolBridge> bridges = new ArrayList<>();
        // 同一次运行的所有工具必须共享门控，才能在任一工具超时、取消或基础设施失败后统一熔断。
        AgentScopeRunGate runGate = new AgentScopeRunGate(options.toolTimeout());
        ReActAgent agent = null;
        RuntimeContext context = null;
        RuntimeException primaryFailure = null;
        try {
            Toolkit toolkit = new Toolkit();
            // ObjectMapper 仅服务于本次运行，Schema 在桥接器构造时即完成校验，避免模型启动后才失败。
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
            // 工具可能产生外部副作用，禁止由 AgentScope 隐式重试；重试策略必须由受治理网关显式决定。
            ExecutionConfig toolConfig = ExecutionConfig.builder()
                    .timeout(options.toolTimeout())
                    .maxAttempts(1)
                    .build();
            // userId 加租户前缀防止不同租户的同名主体在 AgentScope 上下文中碰撞；一次性 runId
            // 作为 sessionId，保持当前同步单轮语义，不引入跨运行会话状态。
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
                    // 只注册经过 CM Agent 治理的业务工具，避免元工具或任务列表形成旁路能力。
                    .enableMetaTool(false)
                    .enableTaskList(false)
                    .build();
            lifecycle.onCreated(agent, context);

            AtomicReference<Msg> finalMessage = new AtomicReference<>();
            // 局部别名使事件回调只捕获已经完成构造的 Agent 和上下文，避免引用后续会变化的生命周期变量。
            ReActAgent activeAgent = agent;
            RuntimeContext activeContext = context;
            agent.streamEvents(new UserMessage(spec.userInput()), context)
                    .doOnNext(event -> {
                        // AgentScope 2.0.0 会把工具结果拆成文本增量和终态事件；先聚合文本，才能在终态时
                        // 精确识别由框架生成、但未经过桥接器完成的工具超时包装。
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
                        if (event instanceof TextBlockDeltaEvent textDeltaEvent
                                && textDeltaEvent.getDelta() != null
                                && !textDeltaEvent.getDelta().isEmpty()) {
                            outputDeltaConsumer.accept(textDeltaEvent.getDelta());
                        }
                        if (event instanceof AgentResultEvent resultEvent) {
                            finalMessage.set(resultEvent.getResult());
                        }
                        throwIfRunAborted(
                                runGate, activeAgent, activeContext, lifecycle);
                    })
                    // Core 的运行时契约是同步的，因此在这里等待整个 AgentScope 事件流完成。
                    .blockLast();

            throwIfRunAborted(runGate, agent, context, lifecycle);
            List<ToolCallRecord> records = collectRecords(bridges);
            return completedResult(finalMessage.get(), records);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            // 基础设施失败可能已被响应式工具链消费，必须先从共享门控恢复并优先向上抛出，
            // 不能把严格审计或持久化故障降级为普通 Provider 失败。
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
                // 工具授权拒绝决定整个 Run 的终态，即使 AgentScope 随后还生成了说明文本。
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
                    // AgentScope 2.0.0 的 ReActAgent.close() 当前为空实现；仍统一调用生命周期契约，
                    // 避免后续框架版本或替代实现开始持有资源后出现成功路径与失败路径的清理差异。
                    lifecycle.close(agent);
                } catch (RuntimeException closeFailure) {
                    // 无主异常时关闭失败必须直接可见；已有主异常时则保留为 suppressed，避免覆盖原始根因。
                    if (primaryFailure == null || primaryFailure == closeFailure) {
                        throw closeFailure;
                    }
                    primaryFailure.addSuppressed(closeFailure);
                }
            }
        }
    }

    /**
     * 恢复共享门控中的基础设施失败，并在重新抛出前中断当前 Agent。
     *
     * <p>中断失败作为 suppressed exception 附加到基础设施失败，既保留严格失败根因，也不丢失
     * AgentScope 生命周期诊断信息。</p>
     *
     * @param runGate 本次运行共享的工具门控
     * @param agent 当前 AgentScope Agent；尚未创建时可为 {@code null}
     * @param context 当前 AgentScope 上下文；尚未创建时可为 {@code null}
     * @param lifecycle AgentScope 生命周期协作者
     */
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

    /**
     * 在每个事件之后检查严格基础设施失败和工具超时，并主动终止事件流。
     *
     * <p>工具超时先中断 Agent，再抛出内部信号进入统一结果映射；基础设施失败则保持原异常向上层传播。</p>
     *
     * @param runGate 本次运行共享的工具门控
     * @param agent 当前 AgentScope Agent
     * @param context 当前 AgentScope 上下文
     * @param lifecycle AgentScope 生命周期协作者
     */
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

    /**
     * 通过运行门控调用 AgentScope 中断，合并来自多个失败分支的竞争请求。
     *
     * @param runGate 本次运行共享的工具门控
     * @param agent 当前 AgentScope Agent
     * @param context 当前 AgentScope 上下文
     * @param lifecycle AgentScope 生命周期协作者
     */
    private static void interruptOnce(
            AgentScopeRunGate runGate,
            ReActAgent agent,
            RuntimeContext context,
            AgentLifecycle lifecycle
    ) {
        runGate.interruptOnce(() -> lifecycle.interrupt(agent, context));
    }

    /**
     * 合并所有工具桥接器此刻已经完成的记录。
     *
     * <p>每个桥接器先返回不可变快照，因此即使事件回调并发结束，聚合过程也不会遍历可变队列。</p>
     *
     * @param bridges 本次运行创建的工具桥接器集合
     * @return 按桥接器顺序合并的工具调用记录
     */
    private static List<ToolCallRecord> collectRecords(List<AgentScopeToolBridge> bridges) {
        return bridges.stream()
                .flatMap(bridge -> bridge.records().stream())
                .toList();
    }

    /**
     * 查找首条授权拒绝记录，用其受控原因决定整个运行终态。
     *
     * @param records 本次运行的工具调用记录
     * @return 首条拒绝记录；不存在时返回 {@code null}
     */
    private static ToolCallRecord findDenied(List<ToolCallRecord> records) {
        return records.stream()
                .filter(record -> record.status() == RunStatus.DENIED)
                .findFirst()
                .orElse(null);
    }

    /**
     * 将 AgentScope 最终消息和工具记录按领域优先级转换为终态。
     *
     * <p>授权拒绝优先于最终消息；没有拒绝但缺少 {@link AgentResultEvent} 时视为失败，
     * 避免事件流异常结束却被误报为成功。普通工具失败不强制运行失败，模型仍可能基于错误结果生成有效答复。</p>
     *
     * @param result AgentScope 最终消息；事件流未产生最终结果时为 {@code null}
     * @param records 本次运行的工具调用记录
     * @return 映射后的适配器终态结果
     */
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

    /**
     * 判断异常链是否表示模型或执行流程超时。
     *
     * <p>除标准 {@link TimeoutException} 外，AgentScope 2.0.0 还可能使用带固定前缀的
     * {@link ModelException} 表达模型请求超时，因此需要同时识别两种形式。</p>
     *
     * @param failure 当前捕获的异常
     * @return 异常链包含已知超时形式时返回 {@code true}
     */
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

    /**
     * 判断异常链是否来自模型 Provider 或其 HTTP 传输层。
     *
     * <p>已知 Provider 故障会转换为固定对外消息，避免响应体、内部 URL 或依赖异常细节越过适配边界；
     * 未知编程错误保持抛出，由上层统一诊断。</p>
     *
     * @param failure 当前捕获的异常
     * @return 异常链包含 AgentScope 模型或传输异常时返回 {@code true}
     */
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

    /**
     * 隔离 AgentScope Agent 创建后、中断与关闭操作的包内生命周期协作者。
     *
     * <p>生产实现直接委托 {@link ReActAgent#interrupt(RuntimeContext)} 和 {@link ReActAgent#close()}；
     * 合同测试可观察调用顺序和失败优先级，而无需替换 ReAct 执行主流程。</p>
     */
    interface AgentLifecycle {

        /**
         * 在 Agent 和 RuntimeContext 均完成构造、事件订阅开始前执行观察钩子。
         *
         * @param agent 当前 AgentScope Agent
         * @param context 本次运行的 AgentScope 上下文
         */
        default void onCreated(ReActAgent agent, RuntimeContext context) {
        }

        /**
         * 使用创建本次运行时的同一上下文中断 Agent。
         *
         * @param agent 当前 AgentScope Agent
         * @param context 本次运行的 AgentScope 上下文
         */
        void interrupt(ReActAgent agent, RuntimeContext context);

        /**
         * 调用 AgentScope Agent 的关闭生命周期契约。
         *
         * <p>AgentScope 2.0.0 的 {@link ReActAgent#close()} 当前不执行额外动作；保留该步骤是为了让
         * 生命周期顺序稳定，并兼容后续框架版本或测试替代实现可能引入的资源释放行为。</p>
         *
         * @param agent 待关闭的 AgentScope Agent
         */
        void close(ReActAgent agent);
    }

    /**
     * 在 Agent 已被中断后终止响应式事件流的内部控制信号。
     *
     * <p>该信号只参与控制流，最终会转换为固定的“Agent 运行超时”失败结果。</p>
     */
    private static final class ToolTimeoutSignal extends RuntimeException {
    }
}
