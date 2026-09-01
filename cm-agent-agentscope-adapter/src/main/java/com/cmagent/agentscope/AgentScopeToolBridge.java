package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 将 CM Agent 工具定义桥接为 AgentScope {@link AgentTool}，同时保留企业治理边界。
 *
 * <p>AgentScope 只看到工具名称、描述和输入 Schema；模型实际发起调用时，本桥接器不会根据工具
 * endpoint 自行联网，而是使用领域请求中已经校验的租户、Agent、主体和运行标识构造
 * {@link ToolInvocationRequest}，交给 {@link ToolInvocationGateway} 再次执行授权、审计和受控调用。</p>
 *
 * <p>每个可见工具对应一个桥接器，同一次运行中的桥接器共享 {@link AgentScopeRunGate}。
 * 工具记录与完成标识使用并发容器，是因为 AgentScope 的工具 Publisher 可能在异步执行链上回调；
 * 对外只能取得不可变快照。</p>
 */
public class AgentScopeToolBridge implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeToolBridge.class);


    private static final String UNEXPECTED_ERROR_MESSAGE = "工具调用失败";
    private static final int DISPLAY_PAYLOAD_MAX_CHARACTERS = 12_000;

    private final AgentRunRequest request;
    private final ToolDefinition tool;
    private final ToolInvocationGateway gateway;
    private final ObjectMapper objectMapper;
    private final AgentScopeRunGate runGate;
    private final Consumer<AgentProgressEvent> progressConsumer;
    private final Map<String, Object> parameters;
    private final ConcurrentLinkedQueue<ToolCallRecord> records = new ConcurrentLinkedQueue<>();
    private final Set<String> completedToolCallIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong fallbackToolCallSequence = new AtomicLong();

    /**
     * 使用独立门控创建可单独使用的工具桥接器。
     *
     * <p>完整 ReAct 运行会通过包内构造器让所有工具共享同一门控；此构造器主要用于独立适配和测试。</p>
     *
     * @param request 已校验且携带可信租户与主体上下文的运行请求
     * @param tool 本次运行允许暴露给模型的工具定义
     * @param gateway 受治理的工具调用网关
     * @param objectMapper 用于校验 Schema 及序列化模型输入的组件
     */
    public AgentScopeToolBridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway,
            ObjectMapper objectMapper
    ) {
        this(request, tool, gateway, objectMapper, new AgentScopeRunGate(), ignored -> {
        });
    }

    /**
     * 保留共享门控但不订阅执行轨迹的既有包内构造方式。
     *
     * @param request 已校验且携带可信租户与主体上下文的运行请求
     * @param tool 本次运行允许暴露给模型的工具定义
     * @param gateway 受治理的工具调用网关
     * @param objectMapper 用于校验 Schema 及序列化模型输入的组件
     * @param runGate 同一次运行中所有工具桥接器共享的门控
     */
    AgentScopeToolBridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway,
            ObjectMapper objectMapper,
            AgentScopeRunGate runGate
    ) {
        this(request, tool, gateway, objectMapper, runGate, ignored -> {
        });
    }

    /**
     * 使用运行级共享门控创建工具桥接器，并在构造阶段校验输入 Schema。
     *
     * <p>Schema 采用快速失败策略：无效工具不会等到模型已经开始运行后才暴露不可调用状态。</p>
     *
     * @param request 已校验且携带可信租户与主体上下文的运行请求
     * @param tool 本次运行允许暴露给模型的工具定义
     * @param gateway 受治理的工具调用网关
     * @param objectMapper 用于校验 Schema 及序列化模型输入的组件
     * @param runGate 同一次运行中所有工具桥接器共享的门控
     * @param progressConsumer 接收尚待服务端纵深脱敏的工具调用进度
     */
    AgentScopeToolBridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway,
            ObjectMapper objectMapper,
            AgentScopeRunGate runGate,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        this.request = Objects.requireNonNull(request, "request 不能为空");
        this.tool = Objects.requireNonNull(tool, "tool 不能为空");
        this.gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.runGate = Objects.requireNonNull(runGate, "runGate 不能为空");
        this.progressConsumer = Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");
        this.parameters = parseParameters(tool.inputSchema());
    }

    /**
     * @return 注册到本次 AgentScope Toolkit 的工具名称
     */
    @Override
    public String getName() {
        return tool.name();
    }

    /**
     * @return 提供给模型选择工具时使用的描述
     */
    @Override
    public String getDescription() {
        return tool.description();
    }

    /**
     * @return 构造阶段已校验且不可修改的工具输入 JSON Schema
     */
    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 创建延迟执行的工具调用 Publisher，并把取消信号传递给运行门控。
     *
     * <p>{@link Mono#fromCallable(java.util.concurrent.Callable)} 只把同步网关包装为响应式类型，不会自行切换
     * 线程或把网关变成非阻塞调用；实际调度由 AgentScope 的工具执行链负责。订阅取消只关闭协作式门控，
     * 已经发生的外部副作用不能由此自动回滚。</p>
     *
     * @param param AgentScope 提供的工具调用参数
     * @return 延迟到订阅时执行的单结果 Publisher
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return withCancellationGate(Mono.fromCallable(() -> invoke(param)));
    }

    /**
     * 在取消信号继续向上游传播前先关闭运行门控。
     *
     * <p>先标记再传播可避免上游取消处理与后续工具调用竞争时，新的调用短暂越过门控。</p>
     *
     * @param source 待附加取消治理的工具结果 Publisher
     * @return 带运行门控取消回调的 Publisher
     */
    Mono<ToolResultBlock> withCancellationGate(Mono<ToolResultBlock> source) {
        return source.doOnCancel(runGate::markInvocationInterrupted);
    }

    /**
     * 获取当前已经完成的工具调用记录。
     *
     * @return 当前桥接器已经完成的工具调用记录不可变快照
     */
    public List<ToolCallRecord> records() {
        return List.copyOf(records);
    }

    /**
     * 重新抛出运行门控记录的首次基础设施失败。
     *
     * <p>供响应式调用方即使消费了 Publisher 异常，也能在外层执行边界恢复严格失败语义。</p>
     */
    public void throwIfInfrastructureFailure() {
        runGate.throwIfInfrastructureFailure();
    }

    /**
     * 判断指定调用是否已由治理网关产生受控终态。
     *
     * <p>执行器使用该信息区分桥接器返回的普通错误与 AgentScope 执行层生成的工具超时包装。</p>
     *
     * @param toolCallId AgentScope 工具调用标识
     * @return 已创建成功、失败或拒绝记录时返回 {@code true}
     */
    boolean hasCompletedToolCall(String toolCallId) {
        return toolCallId != null && completedToolCallIds.contains(toolCallId);
    }

    /**
     * 调用治理网关，记录安全摘要，并将领域结果转换为 AgentScope 工具结果块。
     *
     * <p>租户、Agent、主体、Run 和工具 ID 均来自已校验领域对象；模型只能提供本次工具调用 ID、
     * 工具名称和输入。普通失败与授权拒绝会作为工具错误块返回，让 AgentScope 完成当前推理步骤；
     * 基础设施失败和运行中止必须继续抛出，由外层终止整个 Agent。</p>
     *
     * <p>未知异常只记录固定错误说明，不把序列化异常、工具实现异常或输入值写入领域记录。</p>
     *
     * @param param AgentScope 提供的工具调用参数
     * @return 可返回给 AgentScope 推理循环的工具结果块
     */
    private ToolResultBlock invoke(ToolCallParam param) {
        long startedAt = System.nanoTime();
        ToolUseBlock toolUse = param == null ? null : param.getToolUseBlock();
        Map<String, Object> input = toolUse == null || toolUse.getInput() == null
                ? Map.of()
                : toolUse.getInput();
        String toolCallId = resolveToolCallId(toolUse);
        String invocationToolName = toolUse == null ? "" : toolUse.getName();
        String inputSummary = "输入无法序列化";
        try {
            String inputJson = objectMapper.writeValueAsString(input);
            inputSummary = displayPayload(inputJson);
            progressConsumer.accept(AgentProgressEvent.toolCallStarted(
                    null, toolCallId, tool.name(), inputSummary));
            progressConsumer.accept(AgentProgressEvent.toolCallCompleted(null, toolCallId, tool.name()));
            progressConsumer.accept(AgentProgressEvent.toolExecutionStarted(null, toolCallId, tool.name()));
            // 调用开始用 DEBUG 级别：单次运行可能触发多轮工具调用，INFO 会淹没运行级日志。
            log.debug("工具调用进入治理网关。runId={}, toolId={}, toolName={}, toolCallId={}",
                    request.runId(), tool.id(), tool.name(), toolCallId);
            ToolInvocationResult result = runGate.invoke(gateway, new ToolInvocationRequest(
                    request.tenantId(),
                    request.agentId(),
                    request.principal(),
                    request.runId(),
                    toolCallId,
                    tool.id(),
                    invocationToolName,
                    inputJson
            ));
            Duration duration = elapsedSince(startedAt);
            if (result.success()) {
                completedToolCallIds.add(toolCallId);
                log.info("工具调用成功。runId={}, tenantId={}, toolId={}, toolName={}, toolCallId={}, durationMs={}",
                        request.runId(), request.tenantId(), tool.id(), tool.name(), toolCallId, duration.toMillis());
                String outputSummary = displayPayload(result.output());
                records.add(new ToolCallRecord(
                        tool.id(), toolCallId, tool.name(), inputSummary, outputSummary, RunStatus.SUCCEEDED,
                        duration, true, ""));
                progressConsumer.accept(AgentProgressEvent.toolExecutionCompleted(
                        null, toolCallId, tool.name(), RunStatus.SUCCEEDED, outputSummary, duration.toMillis()));
                return ToolResultBlock.text(result.output()).withState(ToolResultState.SUCCESS);
            }

            RunStatus status = result.authorized() ? RunStatus.FAILED : RunStatus.DENIED;
            completedToolCallIds.add(toolCallId);
            String errorMessage = displayPayload(result.errorMessage());
            records.add(new ToolCallRecord(
                    tool.id(), toolCallId, tool.name(), inputSummary, "", status,
                    duration, result.authorized(), errorMessage));
            progressConsumer.accept(AgentProgressEvent.toolExecutionCompleted(
                    null, toolCallId, tool.name(), status, errorMessage, duration.toMillis()));
            // 失败与拒绝都映射为工具错误块，但日志级别不同：拒绝是安全治理结果必须始终可见；
            // 普通失败可能由工具端可恢复问题导致，用 WARN 记录受控原因。reason 来自网关已脱敏的 errorMessage。
            if (status == RunStatus.DENIED) {
                log.warn("工具调用被授权拒绝。runId={}, tenantId={}, toolId={}, toolName={}, toolCallId={}",
                        request.runId(), request.tenantId(), tool.id(), tool.name(), toolCallId);
            } else {
                log.warn("工具调用失败。runId={}, tenantId={}, toolId={}, toolName={}, toolCallId={}, durationMs={}, reason={}",
                        request.runId(), request.tenantId(), tool.id(), tool.name(), toolCallId, duration.toMillis(), result.errorMessage());
            }
            return ToolResultBlock.error(result.errorMessage()).withState(ToolResultState.ERROR);
        } catch (AgentScopeRunGate.RunAbortedException aborted) {
            throw aborted;
        } catch (ToolInvocationInfrastructureException infrastructureFailure) {
            throw infrastructureFailure;
        } catch (Exception exception) {
            if (isInterruption(exception)) {
                runGate.markInvocationInterrupted();
                throw new AgentScopeRunGate.RunAbortedException();
            }
            completedToolCallIds.add(toolCallId);
            Duration duration = elapsedSince(startedAt);
            records.add(new ToolCallRecord(
                    tool.id(), toolCallId, tool.name(), inputSummary, "", RunStatus.FAILED,
                    duration, false, UNEXPECTED_ERROR_MESSAGE));
            progressConsumer.accept(AgentProgressEvent.toolExecutionCompleted(
                    null, toolCallId, tool.name(), RunStatus.FAILED, UNEXPECTED_ERROR_MESSAGE, duration.toMillis()));
            // 异常消息可能拼接工具入参或上游响应，不能直接作为 Throwable 交给日志框架；
            // 仅记录异常类型和不含 message 的调用栈位置，既保留定位所需的代码路径，也不会泄露载荷。
            log.error("工具调用发生未预期异常。runId={}, tenantId={}, toolId={}, toolName={}, toolCallId={}, "
                            + "exceptionType={}, stack={}",
                    request.runId(), request.tenantId(), tool.id(), tool.name(), toolCallId,
                    exception.getClass().getName(), safeExceptionStack(exception));
            return ToolResultBlock.error(UNEXPECTED_ERROR_MESSAGE).withState(ToolResultState.ERROR);
        }
    }

    /**
     * 提取不含异常消息的有限调用栈，用于在工具异常日志中保留定位信息。
     *
     * <p>工具实现和上游客户端常把请求参数、响应体或凭据拼入异常消息；调用 {@link Throwable#printStackTrace()}
     * 或将 Throwable 直接作为日志参数会原样写出这些内容。这里保留异常类型及最多三层原因链、每层 24 个
     * 类/方法/行号帧，不读取 {@link Throwable#getMessage()}。</p>
     *
     * @param exception 当前捕获的工具调用异常
     * @return 不包含异常消息的限长调用栈摘要
     */
    static String safeExceptionStack(Throwable exception) {
        StringBuilder summary = new StringBuilder();
        Throwable current = exception;
        for (int causeDepth = 0; current != null && causeDepth < 3; causeDepth++) {
            if (causeDepth > 0) {
                summary.append("\nCaused by: ");
            }
            summary.append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            int frameCount = Math.min(frames.length, 24);
            for (int index = 0; index < frameCount; index++) {
                StackTraceElement frame = frames[index];
                summary.append("\n\tat ")
                        .append(frame.getClassName())
                        .append('.')
                        .append(frame.getMethodName())
                        .append('(')
                        .append(frame.getLineNumber())
                        .append(')');
            }
            current = current.getCause();
        }
        return summary.toString();
    }

    /**
     * 解析工具输入 Schema，并要求根节点显式声明为 JSON object。
     *
     * <p>AgentScope 的工具参数契约使用键值映射；拒绝数组、标量或缺少 {@code type=object} 的 Schema，
     * 可以在注册 Toolkit 前暴露配置错误。返回副本不可修改，防止运行期间改变模型可见契约。</p>
     *
     * @param inputSchema 工具定义保存的 JSON Schema
     * @return 保持原字段顺序的不可变参数定义
     * @throws IllegalArgumentException Schema 不是合法 JSON object 时抛出
     */
    private Map<String, Object> parseParameters(String inputSchema) {
        try {
            JsonNode root = objectMapper.readTree(inputSchema);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("工具输入 Schema 必须是 object");
            }
            Map<String, Object> parsed = objectMapper.convertValue(
                    root, new TypeReference<Map<String, Object>>() {
                    });
            if (!"object".equals(parsed.get("type"))) {
                throw new IllegalArgumentException("工具输入 Schema 必须是 object");
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具输入 Schema 不是合法 JSON");
        }
    }

    /**
     * 为缺失框架调用标识的异常输入生成仅在当前运行内使用的稳定关联键。
     *
     * <p>正常 AgentScope 调用会使用 {@link ToolUseBlock#getId()}；该降级分支不会影响网关授权，
     * 只保证前端实时卡片与最终会话内容块仍能对应同一次已执行工具调用。</p>
     *
     * @param toolUse AgentScope 提供的工具调用块，可能为空或缺少标识
     * @return 非空的当前运行内工具调用标识
     */
    private String resolveToolCallId(ToolUseBlock toolUse) {
        if (toolUse != null && toolUse.getId() != null && !toolUse.getId().isBlank()) {
            return toolUse.getId();
        }
        return request.runId() + ":tool:" + fallbackToolCallSequence.getAndIncrement();
    }

    /**
     * 限制工具展示载荷长度，防止超大返回值占满会话消息和浏览器内存。
     *
     * <p>本方法不承担脱敏职责：运行编排服务会在跨 Runtime 边界时统一处理 Secret、Token 和内部地址。
     * 截断标记保留在字段末尾，便于页面说明展示内容并非完整外部响应。</p>
     *
     * @param payload 原始工具输入、返回值或受控错误文本，可为空
     * @return 保留原值或附带截断标记的可展示载荷
     */
    private static String displayPayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        if (payload.length() <= DISPLAY_PAYLOAD_MAX_CHARACTERS) {
            return payload;
        }
        return payload.substring(0, DISPLAY_PAYLOAD_MAX_CHARACTERS) + "\n…（内容超过展示上限，已截断）";
    }

    /**
     * 使用单调时钟计算工具耗时，避免系统时间校准导致负时长。
     *
     * @param startedAt 调用开始时的 {@link System#nanoTime()} 值
     * @return 非负调用时长
     */
    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }

    /**
     * 判断异常链或当前线程状态是否表示协作式中断。
     *
     * <p>部分网关会包装 {@link InterruptedException}，因此不能只检查最外层异常。</p>
     *
     * @param failure 当前捕获的异常
     * @return 异常链包含线程中断，或当前线程已处于中断状态时返回 {@code true}
     */
    private static boolean isInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
