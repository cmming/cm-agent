package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntimeAdapter.class);

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
        return create(credentialProvider, toolGateway, options, clock,
                new io.agentscope.core.state.InMemoryAgentStateStore());
    }

    /**
     * 创建使用外部 AgentStateStore 的可恢复运行时。
     *
     * <p>生产实现应提供加密持久化 Store；内存 Store 仅适用于本地和测试。State Store 的
     * userId/sessionId 均由适配器从可信租户、主体和 runId 构造，不能由浏览器指定。</p>
     */
    public static AgentScopeRuntimeAdapter create(
            ModelCredentialProvider credentialProvider,
            ToolInvocationGateway toolGateway,
            AgentScopeRuntimeOptions options,
            Clock clock,
            AgentStateStore stateStore
    ) {
        return new AgentScopeRuntimeAdapter(
                credentialProvider,
                toolGateway,
                new AgentScopeReActExecutor(options, new AgentScopeModelFactory(), stateStore),
                clock);
    }

    /**
     * 创建同时接入持久化状态和技能读取治理的运行时。
     *
     * @param credentialProvider 按租户和模型配置解析凭据的组件
     * @param toolGateway 每次实际业务工具调用都必须经过的治理入口
     * @param options 模型超时、工具超时和模型尝试次数配置
     * @param clock 提供可测试起止时间的时钟
     * @param stateStore 由服务端持有的 AgentScope 状态仓储
     * @param skillGateway 固定快照的技能正文读取治理入口
     * @return 可直接装配到 Core {@link AgentRuntime} 契约的适配器
     */
    public static AgentScopeRuntimeAdapter create(
            ModelCredentialProvider credentialProvider,
            ToolInvocationGateway toolGateway,
            AgentScopeRuntimeOptions options,
            Clock clock,
            AgentStateStore stateStore,
            SkillAccessGateway skillGateway
    ) {
        return new AgentScopeRuntimeAdapter(
                credentialProvider,
                toolGateway,
                new AgentScopeReActExecutor(options, new AgentScopeModelFactory(), stateStore, skillGateway),
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
                    execution.status() == RunStatus.WAITING_APPROVAL ? null : clock.instant(),
                    execution.errorMessage());
        } catch (ModelCredentialUnavailableException exception) {
            // 凭据解析失败被映射为受控失败终态，此 catch 是该失败唯一可观察的应用日志位置；
            // 只记录租户与模型配置标识及异常类型：失败原因 cause 可能携带配置路径或密钥来源等
            // 运维细节，不写入日志；异常的 {@code getMessage()} 固定为脱敏文本，可直接使用。
            log.warn("模型凭据不可用。runId={}, tenantId={}, modelConfigId={}, exceptionType={}",
                    request.runId(), request.tenantId(), request.modelConfig().id(), exception.getClass().getName());
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

    /**
     * 执行支持内容块快照的会话运行。
     *
     * <p>与 {@link #run(AgentRunRequest, Consumer)} 相比，此入口保留最终 assistant 消息的有序内容块，
     * 供会话服务在运行结束后持久化。文本增量仍在 AgentScope 事件线程中同步交付；上层必须在发送 SSE
     * 前完成脱敏，且不得把工具原始参数或原始结果传给该消费者。</p>
     *
     * @param request 当前领域运行请求；其中的 {@code conversationId} 会决定 AgentScope sessionId
     * @param outputDeltaConsumer 接收带 replyId、blockId 的安全文本增量
     * @return 原运行结果与可选最终 assistant 快照
     */
    @Override
    public AgentRuntimeResult runStructured(
            AgentRunRequest request,
            Consumer<AgentTextDelta> outputDeltaConsumer
    ) {
        return runStructured(request, outputDeltaConsumer, ignored -> {
        });
    }

    /**
     * 执行支持内容块快照和受控执行进度的会话运行。
     *
     * <p>进度消费者只能收到完整思考块以及不带参数、结果载荷的工具生命周期事件；凭据失败发生在
     * AgentScope 执行前，因此不会伪造任何进度事件。</p>
     *
     * @param request 当前领域运行请求
     * @param outputDeltaConsumer 接收最终回答文本增量
     * @param progressConsumer 接收受控执行进度
     * @return 原运行结果与可选最终 assistant 快照
     */
    @Override
    public AgentRuntimeResult runStructured(
            AgentRunRequest request,
            Consumer<AgentTextDelta> outputDeltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");
        Instant startedAt = clock.instant();
        try {
            ModelCredential credential = credentialProvider.resolve(
                    request.tenantId(), request.modelConfig().id());
            AgentScopeExecutionResult execution = executor.executeStructured(
                    toRunSpec(request), credential, toolGateway, outputDeltaConsumer, progressConsumer);
            AgentRunResult run = new AgentRunResult(
                    request.runId(), execution.status(), execution.output(), execution.toolCalls(),
                    startedAt, execution.status() == RunStatus.WAITING_APPROVAL ? null : clock.instant(),
                    execution.errorMessage());
            return new AgentRuntimeResult(run, execution.assistantMessage(), execution.pendingApproval());
        } catch (ModelCredentialUnavailableException exception) {
            // 同 run()：结构化运行路径的凭据失败也不会再向上传播，必须在此处留下 WARN。
            log.warn("模型凭据不可用。runId={}, tenantId={}, modelConfigId={}, exceptionType={}",
                    request.runId(), request.tenantId(), request.modelConfig().id(), exception.getClass().getName());
            return new AgentRuntimeResult(new AgentRunResult(
                    request.runId(), RunStatus.FAILED, "", List.of(), startedAt, clock.instant(),
                    "模型凭据不可用"), null);
        }
    }

    @Override
    public AgentRuntimeResult resumeStructured(
            AgentRunRequest request,
            RuntimeApprovalDecisions decisions,
            Consumer<AgentTextDelta> outputDeltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(decisions, "decisions 不能为空");
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");
        Instant startedAt = clock.instant();
        ModelCredential credential = credentialProvider.resolve(request.tenantId(), request.modelConfig().id());
        AgentScopeExecutionResult execution = executor.resumeStructured(
                toRunSpec(request), credential, toolGateway, decisions, outputDeltaConsumer, progressConsumer);
        AgentRunResult run = new AgentRunResult(
                request.runId(), execution.status(), execution.output(), execution.toolCalls(),
                startedAt, execution.status() == RunStatus.WAITING_APPROVAL ? null : clock.instant(),
                execution.errorMessage());
        return new AgentRuntimeResult(run, execution.assistantMessage(), execution.pendingApproval());
    }
}
