package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.AgentMessageSnapshot;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.RuntimePendingApproval;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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
    private static final int APPROVAL_INPUT_SUMMARY_MAX_CHARACTERS = 12_000;

    private static final Logger log = LoggerFactory.getLogger(AgentScopeReActExecutor.class);

    // 多租户运行共用同一执行器实例，固定中文提示既保证不同失败分支可区分，又避免把底层异常、
    // Provider 响应或内部 URL 透传到 Run 结果与控制台。
    private static final String TIMEOUT_MESSAGE = "Agent 运行超时";
    private static final String FAILURE_MESSAGE = "Agent 运行失败";
    // 当前实际解析的 AgentScope 2.0.2 中，模型超时以 ModelException + 固定英文前缀消息表达，没有专用异常类型可判，
    // 只能通过消息前缀识别；框架升级该文案时必须同步调整此常量与 isTimeoutFailure。
    private static final String MODEL_TIMEOUT_PREFIX = "Model request timeout after ";

    private final AgentScopeRuntimeOptions options;
    private final AgentScopeModelFactory modelFactory;
    private final AgentLifecycle lifecycle;
    private final AgentStateStore stateStore;

    /**
     * 使用真实 AgentScope 中断和关闭操作创建执行器。
     *
     * @param options 模型与工具执行策略
     * @param modelFactory 根据领域配置和受控凭据创建模型的工厂
     */
    AgentScopeReActExecutor(AgentScopeRuntimeOptions options, AgentScopeModelFactory modelFactory) {
        this(options, modelFactory, new InMemoryAgentStateStore(), new AgentLifecycle() {
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
        this(options, modelFactory, new InMemoryAgentStateStore(), lifecycle);
    }

    /** 使用外部持久化 State Store 创建可跨实例恢复的执行器。 */
    AgentScopeReActExecutor(
            AgentScopeRuntimeOptions options,
            AgentScopeModelFactory modelFactory,
            AgentStateStore stateStore
    ) {
        this(options, modelFactory, stateStore, new AgentLifecycle() {
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
            AgentStateStore stateStore,
            AgentLifecycle lifecycle
    ) {
        this.options = Objects.requireNonNull(options, "options 不能为空");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory 不能为空");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore 不能为空");
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
        return executeStructured(spec, credential, toolGateway,
                delta -> outputDeltaConsumer.accept(delta.delta()));
    }

    /**
     * 执行会话运行，并把带 replyId、blockId 的文本块增量交付给调用方。
     *
     * <p>这是生产执行主流程：文本、工具、超时与最终结果事件均在此归并为
     * {@link AgentScopeExecutionResult}。最终 assistant 消息会按内容块顺序过滤为
     * 受控快照，避免工具原始输入输出越过适配边界。</p>
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param outputDeltaConsumer 接收带块标识的安全文本增量的非空消费者
     * @return 已归并模型输出、工具记录与最终消息快照的终态结果
     */
    @Override
    public AgentScopeExecutionResult executeStructured(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            Consumer<AgentTextDelta> outputDeltaConsumer
    ) {
        return executeStructured(spec, credential, toolGateway, outputDeltaConsumer, ignored -> {
        });
    }

    @Override
    public AgentScopeExecutionResult executeStructured(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            Consumer<AgentTextDelta> outputDeltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        return executeInternal(spec, credential, toolGateway, null, outputDeltaConsumer, progressConsumer);
    }

    @Override
    public AgentScopeExecutionResult resumeStructured(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            RuntimeApprovalDecisions decisions,
            Consumer<AgentTextDelta> outputDeltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        return executeInternal(spec, credential, toolGateway,
                Objects.requireNonNull(decisions, "decisions 不能为空"), outputDeltaConsumer, progressConsumer);
    }

    /**
     * 执行会话运行，并转发完整思考块和无原始载荷的工具生命周期事件。
     *
     * <p>thinking 增量先在适配器内按块聚合，只有结束事件到达后才交给上层整体脱敏；工具参数增量和
     * 工具结果文本增量只供 AgentScope 与运行门控内部使用，不进入 {@code progressConsumer}。</p>
     *
     * @param spec 已校验领域请求的适配器视图
     * @param credential 当前租户与模型配置对应的受控凭据
     * @param toolGateway 每次实际工具调用都必须经过的治理入口
     * @param outputDeltaConsumer 接收带块标识的最终回答文本增量
     * @param progressConsumer 接收受控执行进度
     * @return 已归并模型输出、工具记录与最终消息快照的终态结果
     */
    private AgentScopeExecutionResult executeInternal(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway,
            RuntimeApprovalDecisions decisions,
            Consumer<AgentTextDelta> outputDeltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(spec, "spec 不能为空");
        Objects.requireNonNull(credential, "credential 不能为空");
        Objects.requireNonNull(toolGateway, "toolGateway 不能为空");
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");

        List<AgentScopeToolBridge> bridges = new ArrayList<>();
        // 同一次运行的所有工具必须共享门控，才能在任一工具超时、取消或基础设施失败后统一熔断。
        AgentScopeRunGate runGate = new AgentScopeRunGate(options.toolTimeout());
        ReActAgent agent = null;
        RuntimeContext context = null;
        RuntimeException primaryFailure = null;
        boolean preserveCheckpoint = false;
        // 运行级日志使用 runId 作为主关联键，便于与上层 Run 日志及错误 errorId 对照排查。
        log.info("AgentScope 运行开始。runId={}, tenantId={}, agentId={}, principalId={}, toolCount={}, modelTimeout={}, toolTimeout={}",
                spec.runId(), spec.tenantId(), spec.agentId(), spec.principalId(),
                spec.request().tools().size(), options.modelTimeout(), options.toolTimeout());
        try {
            Toolkit toolkit = new Toolkit();
            // ObjectMapper 仅服务于本次运行，Schema 在桥接器构造时即完成校验，避免模型启动后才失败。
            ObjectMapper objectMapper = new ObjectMapper();
            spec.request().tools().forEach(tool -> {
                AgentScopeToolBridge bridge =
                        new AgentScopeToolBridge(
                                spec.request(), tool, toolGateway, objectMapper, runGate, progressConsumer);
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
            // userId 加租户前缀防止同名主体跨租户碰撞；sessionId 固定使用 runId，使长期会话历史与
            // 本次可恢复运行检查点完全分离，审批恢复不会把上一轮 AgentState 带入新 Run。
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
                    .permissionContext(permissionContext(
                            spec.request().tools(), options.permissionEnabled(), spec.request().conversationId() != null))
                    .stateStore(stateStore)
                    .defaultSessionId(spec.runId().toString())
                    // 只注册经过 CM Agent 治理的业务工具，避免元工具或任务列表形成旁路能力。
                    .enableMetaTool(false)
                    .enableTaskList(false)
                    .build();
            lifecycle.onCreated(agent, context);

            AtomicReference<Msg> finalMessage = new AtomicReference<>();
            AtomicReference<RequireUserConfirmEvent> approvalEvent = new AtomicReference<>();
            Map<String, ThinkingProgress> thinkingByBlock = new HashMap<>();
            // 局部别名使事件回调只捕获已经完成构造的 Agent 和上下文，避免引用后续会变化的生命周期变量。
            ReActAgent activeAgent = agent;
            RuntimeContext activeContext = context;
            Msg inputMessage = decisions == null
                    ? new UserMessage(spec.userInput())
                    : resumeMessage(agent, context, decisions, objectMapper, spec.request().tools());
            agent.streamEvents(inputMessage, context)
                    .doOnNext(event -> {
                        if (event instanceof RequireUserConfirmEvent required) {
                            approvalEvent.set(required);
                        }
                        if (event instanceof ThinkingBlockStartEvent thinkingStart) {
                            String key = thinkingKey(thinkingStart.getReplyId(), thinkingStart.getBlockId());
                            thinkingByBlock.put(key, new ThinkingProgress(
                                    thinkingStart.getReplyId(), thinkingStart.getBlockId(), new StringBuilder()));
                            progressConsumer.accept(AgentProgressEvent.thinkingStarted(
                                    thinkingStart.getReplyId(), thinkingStart.getBlockId()));
                        }
                        if (event instanceof ThinkingBlockDeltaEvent thinkingDelta
                                && thinkingDelta.getDelta() != null) {
                            String key = thinkingKey(thinkingDelta.getReplyId(), thinkingDelta.getBlockId());
                            ThinkingProgress progress = thinkingByBlock.computeIfAbsent(key, ignored -> {
                                progressConsumer.accept(AgentProgressEvent.thinkingStarted(
                                        thinkingDelta.getReplyId(), thinkingDelta.getBlockId()));
                                return new ThinkingProgress(
                                        thinkingDelta.getReplyId(), thinkingDelta.getBlockId(), new StringBuilder());
                            });
                            progress.content().append(thinkingDelta.getDelta());
                        }
                        if (event instanceof ThinkingBlockEndEvent thinkingEnd) {
                            ThinkingProgress progress = thinkingByBlock.remove(
                                    thinkingKey(thinkingEnd.getReplyId(), thinkingEnd.getBlockId()));
                            if (progress != null && !progress.content().isEmpty()) {
                                progressConsumer.accept(AgentProgressEvent.thinkingCompleted(
                                        progress.replyId(), progress.blockId(), progress.content().toString()));
                            }
                        }
                        // 当前实际解析的 AgentScope 2.0.2 会把工具结果拆成文本增量和终态事件；先聚合文本，才能在终态时
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
                            outputDeltaConsumer.accept(new AgentTextDelta(
                                    textDeltaEvent.getReplyId(), textDeltaEvent.getBlockId(),
                                    textDeltaEvent.getDelta()));
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
            // 正常路径在结果映射前记录一次事件流收尾，便于把工具记录数量与最终消息存在性关联排查。
            log.info("AgentScope 事件流正常结束。runId={}, toolCallCount={}, hasFinalMessage={}",
                    spec.runId(), records.size(), finalMessage.get()  != null);
            AgentScopeExecutionResult completed = completedResult(
                    finalMessage.get(), records, approvalEvent.get(), spec.request().tools(), objectMapper);
            preserveCheckpoint = completed.status() == RunStatus.WAITING_APPROVAL;
            return completed;
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
                // 拒绝属于可预期的治理结果，用 WARN 记录命中边界而不是 ERROR。
                log.warn("AgentScope 运行因工具授权拒绝终止。runId={}, tenantId={}, agentId={}, deniedToolId={}, deniedToolName={}",
                        spec.runId(), spec.tenantId(), spec.agentId(), denied.toolId(), denied.toolName());
                return AgentScopeExecutionResult.denied(denied.errorMessage(), records);
            }
            if (timedOut) {
                primaryFailure = null;
                // 超时属于运行期可恢复问题，用 WARN 记录，提示调整 modelTimeout 或 toolTimeout。
                log.warn("AgentScope 运行超时。runId={}, tenantId={}, modelTimeout={}, toolTimeout={}, pendingToolCalls={}",
                        spec.runId(), spec.tenantId(), options.modelTimeout(), options.toolTimeout(), records.size());
                return AgentScopeExecutionResult.failed(TIMEOUT_MESSAGE, records);
            }
            if (isProviderFailure(exception)) {
                primaryFailure = null;
                // 只记录异常类型与可关联键；Provider 响应体、内部 URL 等细节不进入日志。
                log.warn("AgentScope 运行遭遇模型 Provider 失败。runId={}, tenantId={}, exceptionType={}",
                        spec.runId(), spec.tenantId(), exception.getClass().getName());
                return AgentScopeExecutionResult.failed(FAILURE_MESSAGE, records);
            }
            throw exception;
        } finally {
            RuntimeException finalizationFailure = null;
            if (context != null && !preserveCheckpoint) {
                // 只有 ASK 暂停需要保留状态；成功、拒绝、超时、Provider 故障和未知异常均删除状态槽，
                // 防止终态 Run 留下可被误恢复的 ToolUseBlock。
                try {
                    stateStore.delete(context.getUserId(), context.getSessionId());
                } catch (RuntimeException cleanupFailure) {
                    finalizationFailure = cleanupFailure;
                }
            }
            if (agent != null) {
                try {
                    // 当前实际解析的 AgentScope 2.0.2 中 ReActAgent.close() 为空实现；仍统一调用生命周期契约，
                    // 避免后续框架版本或替代实现开始持有资源后出现成功路径与失败路径的清理差异。
                    lifecycle.close(agent);
                } catch (RuntimeException closeFailure) {
                    if (finalizationFailure == null) {
                        finalizationFailure = closeFailure;
                    } else if (finalizationFailure != closeFailure) {
                        finalizationFailure.addSuppressed(closeFailure);
                    }
                }
            }
            if (finalizationFailure != null) {
                // 先完成状态与 Agent 两项清理；已有主异常时把清理失败附加其后，避免覆盖原始根因。
                if (primaryFailure == null || primaryFailure == finalizationFailure) {
                    throw finalizationFailure;
                }
                primaryFailure.addSuppressed(finalizationFailure);
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
        return completedResult(result, records, null, List.of(), new ObjectMapper());
    }

    static AgentScopeExecutionResult completedResult(
            Msg result,
            List<ToolCallRecord> records,
            RequireUserConfirmEvent approvalEvent,
            List<ToolDefinition> tools,
            ObjectMapper objectMapper
    ) {
        ToolCallRecord denied = findDenied(records);
        if (denied != null) {
            String output = result == null ? "" : result.getTextContent();
            return new AgentScopeExecutionResult(
                    RunStatus.DENIED, output, records, denied.errorMessage(), safeMessage(result, records));
        }
        if (result == null) {
            return AgentScopeExecutionResult.failed(FAILURE_MESSAGE, records);
        }
        if (result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
            List<ToolUseBlock> asking = approvalEvent == null
                    ? result.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(block -> block.getState() == ToolCallState.ASKING)
                    .toList()
                    : approvalEvent.getToolCalls();
            return AgentScopeExecutionResult.waiting(
                    pendingApproval(result.getId(), asking, tools, objectMapper), records);
        }
        return AgentScopeExecutionResult.succeeded(
                result.getTextContent(), records, safeMessage(result, records));
    }

    /** 构造在线 DEFAULT 或无会话 DONT_ASK 模式下的第一版固定规则。 */
    static PermissionContextState permissionContext(List<ToolDefinition> tools, boolean permissionEnabled) {
        return permissionContext(tools, permissionEnabled, true);
    }

    /**
     * 构造 LOW/MEDIUM 明确允许、HIGH 每次询问的固定规则。
     *
     * <p>只有持久化会话具备审批查询和恢复入口，因此无会话 Run 必须使用 DONT_ASK，
     * 让 AgentScope 将 ASK 安全转换为拒绝，不能留下没有审批请求的 WAITING_APPROVAL。</p>
     */
    static PermissionContextState permissionContext(
            List<ToolDefinition> tools, boolean permissionEnabled, boolean interactive) {
        var builder = PermissionContextState.builder()
                .mode(interactive ? PermissionMode.DEFAULT : PermissionMode.DONT_ASK);
        for (ToolDefinition tool : tools) {
            PermissionBehavior behavior = permissionEnabled && tool.riskLevel() == ToolRiskLevel.HIGH
                    ? PermissionBehavior.ASK
                    : PermissionBehavior.ALLOW;
            PermissionRule rule = new PermissionRule(tool.name(), null, behavior, "cm-agent-policy-v1");
            if (behavior == PermissionBehavior.ASK) {
                builder.addAskRule(tool.name(), rule);
            } else {
                builder.addAllowRule(tool.name(), rule);
            }
        }
        return builder.build();
    }

    /** 从持久化 AgentState 中提取 ASKING 块并构造完整 ConfirmResult 消息。 */
    private static Msg resumeMessage(
            ReActAgent agent,
            RuntimeContext context,
            RuntimeApprovalDecisions decisions,
            ObjectMapper objectMapper,
            List<ToolDefinition> authorizedTools
    ) {
        List<ToolUseBlock> asking = agent.getAgentState(context).getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(block -> block.getState() == ToolCallState.ASKING)
                .toList();
        Map<String, RuntimeApprovalDecisions.Item> byCall = new HashMap<>();
        for (RuntimeApprovalDecisions.Item item : decisions.items()) {
            if (byCall.put(item.toolCallId(), item) != null) {
                throw new IllegalArgumentException("审批决定包含重复 toolCallId");
            }
        }
        Set<String> expected = asking.stream().map(ToolUseBlock::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(byCall.keySet())) {
            throw new IllegalArgumentException("审批决定未完整覆盖当前待确认工具");
        }
        for (ToolUseBlock tool : asking) {
            // 检查点内工具名只用于定位，批准仍绑定原工具 ID；删除后重建同名工具不能继承旧批准。
            RuntimeApprovalDecisions.Item decision = byCall.get(tool.getId());
            if (decision.decision() == ToolApprovalDecision.APPROVE
                    && authorizedTools.stream().noneMatch(candidate -> candidate.name().equals(tool.getName())
                    && candidate.id().equals(decision.toolId()))) {
                throw new IllegalStateException("待审批工具已变更或失去授权，不能继续执行");
            }
            String actualHash = approvalInputHash(objectMapper, tool.getInput());
            if (!actualHash.equals(byCall.get(tool.getId()).inputHash())) {
                throw new IllegalStateException("待审批工具输入已变化，不能继续执行");
            }
        }
        List<ConfirmResult> results = asking.stream()
                .map(tool -> new ConfirmResult(
                        byCall.get(tool.getId()).decision() == ToolApprovalDecision.APPROVE, tool))
                .toList();
        return Msg.builder().name("user").role(MsgRole.USER)
                .textContent("工具审批决定已提交")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    /** 将原始 ToolUseBlock 限制为服务端审批所需的安全摘要与绑定哈希。 */
    private static RuntimePendingApproval pendingApproval(
            String replyId,
            List<ToolUseBlock> asking,
            List<ToolDefinition> tools,
            ObjectMapper objectMapper
    ) {
        Map<String, ToolDefinition> byName = tools.stream().collect(java.util.stream.Collectors.toMap(
                ToolDefinition::name, java.util.function.Function.identity()));
        List<RuntimePendingApproval.RuntimePendingToolCall> items = asking.stream().map(block -> {
            ToolDefinition tool = byName.get(block.getName());
            if (tool == null) {
                throw new IllegalStateException("待审批工具不在本次授权快照中");
            }
            String input = canonicalJson(objectMapper, block.getInput());
            return new RuntimePendingApproval.RuntimePendingToolCall(
                    block.getId(), tool.id(), tool.name(), tool.riskLevel(),
                    approvalInputSummary(input), approvalInputHash(objectMapper, block.getInput()));
        }).toList();
        return new RuntimePendingApproval(replyId, items);
    }

    private static String canonicalJson(ObjectMapper objectMapper, Map<String, Object> input) {
        try {
            return objectMapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(input == null ? Map.of() : input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("待审批工具输入无法规范化", exception);
        }
    }

    /**
     * 对规范化工具输入计算审批绑定哈希。
     *
     * <p>哈希只用于检测暂停到恢复期间的输入漂移，不用于替代检查点加密或访问控制。</p>
     */
    static String approvalInputHash(ObjectMapper objectMapper, Map<String, Object> input) {
        String value = canonicalJson(objectMapper, input);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    /** 将前端可见审批摘要限制在固定长度，完整输入只存在于加密检查点。 */
    static String approvalInputSummary(String input) {
        if (input.length() <= APPROVAL_INPUT_SUMMARY_MAX_CHARACTERS) {
            return input;
        }
        return input.substring(0, APPROVAL_INPUT_SUMMARY_MAX_CHARACTERS) + "\n…（内容超过展示上限，已截断）";
    }

    /**
     * 将 AgentScope 最终消息映射为只含思考、回答文本和受治理工具摘要的 Core 快照。
     *
     * <p>思考与回答文本仍会在服务端运行边界统一脱敏。框架最终消息中的工具块可能因 Provider 实现而丢失，
     * 因此这里始终使用本次运行已保存的 {@link ToolCallRecord}；不会回退到 AgentScope 原始工具参数或响应。</p>
     */
    private static AgentMessageSnapshot safeMessage(Msg result, List<ToolCallRecord> records) {
        if (result == null) {
            return null;
        }
        List<MessageContentBlock> blocks = new ArrayList<>();
        boolean appendedToolRecords = false;
        for (ContentBlock block : result.getContent()) {
            if (block instanceof ThinkingBlock thinkingBlock && thinkingBlock.getThinking() != null
                    && !thinkingBlock.getThinking().isBlank()) {
                blocks.add(MessageContentBlock.thinking(thinkingBlock.getThinking()));
            } else if (block instanceof TextBlock textBlock && textBlock.getText() != null
                    && !textBlock.getText().isBlank()) {
                if (!appendedToolRecords) {
                    appendToolBlocks(blocks, records);
                    appendedToolRecords = true;
                }
                blocks.add(MessageContentBlock.text(textBlock.getText()));
            }
        }
        if (!appendedToolRecords) {
            appendToolBlocks(blocks, records);
        }
        if (blocks.isEmpty() && result.getTextContent() != null && !result.getTextContent().isBlank()) {
            appendToolBlocks(blocks, records);
            blocks.add(MessageContentBlock.text(result.getTextContent()));
        }
        return blocks.isEmpty() ? null : new AgentMessageSnapshot(result.getId(), result.getName(), blocks);
    }

    /**
     * 以治理网关已经确认的调用记录构造会话工具块，而非依赖 AgentScope 最终消息保留工具块。
     *
     * <p>部分 Provider 会在最终 {@link Msg} 中仅保留回答文本；若以它作为工具信息唯一来源，前端会丢失
     * 已实际执行的工具调用。此处使用桥接器记录的稳定调用标识、可展示输入/输出快照与单调时钟耗时，
     * 由外层服务在落库前统一脱敏。</p>
     *
     * @param blocks 当前正在构造的 assistant 内容块
     * @param records 本次实际完成的工具调用记录，保持桥接器收集顺序
     */
    private static void appendToolBlocks(List<MessageContentBlock> blocks, List<ToolCallRecord> records) {
        for (ToolCallRecord record : records) {
            if (record == null || record.toolCallId() == null || record.toolCallId().isBlank()) {
                continue;
            }
            blocks.add(MessageContentBlock.toolUse(
                    record.toolCallId(), record.toolName(), record.inputSummary()));
            String summary = record.status() == RunStatus.SUCCEEDED
                    ? record.outputSummary()
                    : record.errorMessage();
            Long durationMillis = record.duration() == null ? null : record.duration().toMillis();
            blocks.add(MessageContentBlock.toolResult(
                    record.toolCallId(), record.status(), summary, durationMillis));
        }
    }

    private static String thinkingKey(String replyId, String blockId) {
        return String.valueOf(replyId) + '\u0000' + String.valueOf(blockId);
    }

    /** 保存尚未完成的思考块，内容只在块结束后离开适配器。 */
    private record ThinkingProgress(String replyId, String blockId, StringBuilder content) {
    }

    /**
     * 判断异常链是否表示模型或执行流程超时。
     *
     * <p>除标准 {@link TimeoutException} 外，当前实际解析的 AgentScope 2.0.2 还可能使用带固定前缀的
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
         * <p>当前实际解析的 AgentScope 2.0.2 中 {@link ReActAgent#close()} 不执行额外动作；保留该步骤是为了让
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
