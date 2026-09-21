package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentMessageSnapshot;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@Service
/** 编排 Agent 单轮运行，连接运行时、工具治理、持久化和输出脱敏边界。 */
public class RunExecutionService {
    private static final String CONTROLLED_FAILURE = "Agent 运行失败";
    private static final int EXECUTION_TRACE_PAYLOAD_MAX_CHARACTERS = 12_000;
    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);

    private final AgentRuntime runtime;
    private final AgentDefinitionRepository agentRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final RunPersistenceService persistenceService;
    private final SensitiveDataRedactor redactor;
    private final ErrorDiagnosticLogger diagnosticLogger;
    private final SkillRuntimeService skillRuntimeService;

    @Autowired
    /**
     * 创建 {@code RunExecutionService} 实例并保存其运行所需依赖。
     *
     * @param runtime 执行 Agent 请求的运行时实现
     * @param agentRepository 负责访问领域数据的仓储。
     * @param modelConfigRepository 负责访问领域数据的仓储。
     * @param toolRepository 负责访问领域数据的仓储。
     * @param grantRepository 负责访问领域数据的仓储。
     * @param toolAuthorizationPolicy 校验 Agent 工具授权关系的策略
     * @param persistenceService 负责当前业务流程的服务。
     * @param redactor 负责清理敏感文本的脱敏器。
     * @param diagnosticLogger 记录运行失败的脱敏诊断日志。
     * @param skillRuntimeService 在进入 Runtime 前固定或恢复技能快照。
     */
    public RunExecutionService(
            AgentRuntime runtime,
            AgentDefinitionRepository agentRepository,
            ModelConfigRepository modelConfigRepository,
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            RunPersistenceService persistenceService,
            SensitiveDataRedactor redactor,
            ErrorDiagnosticLogger diagnosticLogger,
            SkillRuntimeService skillRuntimeService
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.modelConfigRepository = Objects.requireNonNull(modelConfigRepository, "modelConfigRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.toolAuthorizationPolicy = Objects.requireNonNull(toolAuthorizationPolicy, "toolAuthorizationPolicy 不能为空");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
        this.diagnosticLogger = Objects.requireNonNull(diagnosticLogger, "diagnosticLogger 不能为空");
        this.skillRuntimeService = Objects.requireNonNull(skillRuntimeService, "skillRuntimeService 不能为空");
    }

    /**
     * 执行 Agent 的单轮运行，并持久化运行状态和工具调用结果。
     *
     * <p>调用方已完成运行权限校验；本方法负责在租户边界内校验运行资源、筛选授权工具，
     * 创建运行记录后调用 Runtime。执行成功时持久化终态和工具调用并返回脱敏结果；执行失败时
     * 根据异常类型完成失败状态与审计收口，并保留审计或数据库异常的原始语义。</p>
     *
     * @param principal 当前认证主体
     * @param agentId   待运行的 Agent 标识
     * @param input     用户输入
     * @return Agent 运行结果
     * @throws ResponseStatusException   Agent 不存在、未启用或不可运行时抛出
     * @throws RuntimeExecutionException 运行时或受治理工具调用失败时抛出
     */
    public AgentRunResult run(PrincipalRef principal, UUID agentId, String input) {
        return run(principal, agentId, input, ignored -> {
        });
    }

    /**
     * 执行 Agent 单轮运行，并在模型输出可用时向调用方发送已脱敏的文本片段。
     *
     * <p>运行记录仍只在最终结果产生后落库，避免把不完整输出写成已完成结果；流式消费者仅用于
     * 当前连接的即时展示，且所有片段都先经过与最终输出相同的脱敏边界。</p>
     *
     * @param principal 当前认证主体
     * @param agentId 待运行的 Agent 标识
     * @param input 用户输入
     * @param outputDeltaConsumer 接收脱敏文本增量的消费者
     * @return Agent 运行结果
     */
    public AgentRunResult run(
            PrincipalRef principal,
            UUID agentId,
            String input,
            Consumer<String> outputDeltaConsumer
    ) {
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        ResolvedRunContext context = resolve(principal, agentId);
        RunRecord runningRun = persistenceService.start(principal, context.agent().id(), input);
        return executePrepared(principal, context, runningRun, input, null, null,
                delta -> outputDeltaConsumer.accept(delta.delta()), ignored -> {
                }).run();
    }

    /**
     * 使用已经落库的 RUNNING 记录执行会话运行，避免重复创建 Run。
     *
     * <p>调用方必须先在短事务中完成 USER 消息和该 Run 的创建；本方法只执行模型、工具治理和既有的
     * Run 收口流程。{@code conversationId} 仅传递给 Runtime 用于选择会话 session，不能替代运行记录的
     * tenant、Agent 归属校验。</p>
     *
     * @param principal 提供可信租户和主体的已认证上下文
     * @param agentId 预创建 Run 必须归属的 Agent 标识
     * @param runningRun 已持久化且仍处于 {@code RUNNING} 的运行记录
     * @param runtimeInput 已拼接历史边界并脱敏后的本轮输入
     * @param conversationId 可选的会话标识；传统单轮运行传 {@code null}
     * @param deltaConsumer 接收已在本服务边界脱敏的文本增量
     * @return 带持久化 Run 终态和可选 assistant 安全快照的结果
     */
    public AgentRuntimeResult runPrepared(
            PrincipalRef principal,
            UUID agentId,
            RunRecord runningRun,
            String runtimeInput,
            UUID conversationId,
            Consumer<AgentTextDelta> deltaConsumer
    ) {
        return runPrepared(principal, agentId, runningRun, runtimeInput, conversationId, deltaConsumer, ignored -> {
        });
    }

    /**
     * 使用已经落库的 RUNNING 记录执行会话运行，并转发受控执行进度。
     *
     * <p>思考内容、工具入参、返回值和受控错误都在此可信运行边界完成脱敏与限长；工具进度可携带
     * 工具名称、调用标识、终态和可展示快照，但调用方不能通过该通道取得未经处理的工具参数或结果。</p>
     *
     * @param principal 提供可信租户和主体的已认证上下文
     * @param agentId 预创建 Run 必须归属的 Agent 标识
     * @param runningRun 已持久化且仍处于 {@code RUNNING} 的运行记录
     * @param runtimeInput 已拼接历史边界并脱敏后的本轮输入
     * @param conversationId 可选的会话标识
     * @param deltaConsumer 接收已脱敏的最终回答文本增量
     * @param progressConsumer 接收已脱敏的执行进度
     * @return 带持久化 Run 终态和可选 assistant 安全快照的结果
     */
    public AgentRuntimeResult runPrepared(
            PrincipalRef principal,
            UUID agentId,
            RunRecord runningRun,
            String runtimeInput,
            UUID conversationId,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(runningRun, "runningRun 不能为空");
        Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");
        if (!principal.tenantId().equals(runningRun.tenantId()) || !agentId.equals(runningRun.agentId())) {
            throw new IllegalArgumentException("预创建 Run 不属于当前租户或 Agent");
        }
        return executePrepared(
                principal, resolve(principal, agentId), runningRun, runtimeInput, conversationId, null,
                deltaConsumer, progressConsumer);
    }

    /**
     * 使用原 runId、原发起主体和最新工具授权快照恢复 WAITING_APPROVAL 运行。
     *
     * <p>恢复前重新解析 Agent、模型和 ToolGrant；审批只解除 AgentScope ASK，不会跳过
     * 受治理网关的执行前授权、租户与工具状态复核。</p>
     */
    public AgentRuntimeResult resumePrepared(
            PrincipalRef principal,
            UUID agentId,
            RunRecord waitingRun,
            UUID conversationId,
            RuntimeApprovalDecisions decisions,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(waitingRun, "waitingRun 不能为空");
        if (waitingRun.status() != com.cmagent.core.domain.RunStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("只能恢复 WAITING_APPROVAL 运行");
        }
        if (!principal.tenantId().equals(waitingRun.tenantId()) || !agentId.equals(waitingRun.agentId())) {
            throw new IllegalArgumentException("待恢复 Run 不属于当前租户或 Agent");
        }
        // 第一版只允许原发起人恢复，不能把审批人或另一个内部调用者的权限借给旧 Run。
        if (!principal.principalId().equals(waitingRun.principalId())) {
            throw new IllegalArgumentException("待恢复 Run 不属于当前发起主体");
        }
        return executePrepared(principal, resolve(principal, agentId), waitingRun, "", conversationId,
                Objects.requireNonNull(decisions, "decisions 不能为空"), deltaConsumer, progressConsumer);
    }

    private AgentRuntimeResult executePrepared(
            PrincipalRef principal,
            ResolvedRunContext context,
            RunRecord runningRun,
            String runtimeInput,
            UUID conversationId,
            RuntimeApprovalDecisions approvalDecisions,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Objects.requireNonNull(deltaConsumer, "deltaConsumer 不能为空");
        Objects.requireNonNull(progressConsumer, "progressConsumer 不能为空");

        AgentRuntimeResult runtimeEnvelope;
        try {
            // 将完整运行上下文交给 Runtime；Runtime 内部可能继续发起受治理的工具调用。
            var skills = approvalDecisions == null
                    ? skillRuntimeService.prepare(principal, runningRun)
                    : skillRuntimeService.restore(principal, runningRun);
            AgentRunRequest runtimeRequest = new AgentRunRequest(
                    runningRun.id(), principal.tenantId(), context.agent(), context.modelConfig(), principal,
                    runtimeInput, context.authorizedTools(), conversationId, skills.versions()
            );
            Consumer<AgentTextDelta> safeDelta = delta -> deltaConsumer.accept(new AgentTextDelta(
                    delta.replyId(), delta.blockId(), redactor.redact(delta.delta())));
            Consumer<AgentProgressEvent> safeProgress = progress -> progressConsumer.accept(redactProgress(progress));
            runtimeEnvelope = approvalDecisions == null
                    ? runtime.runStructured(runtimeRequest, safeDelta, safeProgress)
                    : runtime.resumeStructured(runtimeRequest, approvalDecisions, safeDelta, safeProgress);
        } catch (AuditPersistenceException auditFailure) {
            // 审计持久化失败时尽力关闭运行记录，并保留原异常交给上层严格处理。
            bestEffortFailureClosure(principal, runningRun);
            throw auditFailure;
        } catch (DataAccessException dataFailure) {
            // 业务数据持久化失败同样尝试关闭运行记录，避免掩盖原始数据库异常。
            bestEffortFailureClosure(principal, runningRun);
            throw dataFailure;
        } catch (SkillAccessException skillFailure) {
            // 技能异常已经具备稳定错误码和错误编号；只收口 Run，不能包装成泛化运行错误后丢失语义。
            try {
                persistenceService.completeFailure(principal, runningRun);
            } catch (RuntimeException closureFailure) {
                closureFailure.addSuppressed(skillFailure);
                throw closureFailure;
            }
            throw skillFailure;
        } catch (RuntimeException runtimeFailure) {
            // 普通 Runtime 异常先记录可关联诊断，再依次尝试完成失败状态和失败审计。
            diagnosticLogger.error(new ErrorDiagnosticLogger.DiagnosticContext(
                    runningRun.id().toString(), "AGENT_RUNTIME", "RUNTIME_EXECUTION_FAILED",
                    principal.tenantId().toString(), principal.principalId(), context.agent().id().toString(),
                    runningRun.id().toString(), "-", "-", "AGENT"
            ), runtimeFailure);
            try {
                persistenceService.completeFailure(principal, runningRun);
            } catch (AuditPersistenceException | DataAccessException failureClosureFailure) {
                failureClosureFailure.addSuppressed(runtimeFailure);
                throw failureClosureFailure;
            } catch (RuntimeException failureClosureFailure) {
                log.warn("运行失败收口未完成。runId={}, reason={}",
                        runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
            }
            try {
                bestEffortFailureAudit(principal, runningRun);
            } catch (AuditPersistenceException auditFailure) {
                auditFailure.addSuppressed(runtimeFailure);
                throw auditFailure;
            }
            throw new RuntimeExecutionException(runtimeFailure);
        }

        // Runtime 成功返回后持久化运行终态与工具调用，再基于持久化记录构造脱敏响应。
        AgentRunResult runtimeResult = runtimeEnvelope.run();
        if (runtimeResult.status() == com.cmagent.core.domain.RunStatus.WAITING_APPROVAL) {
            // 每次 ASK 都先保存当前片段已发生的工具事实；重复等待状态由持久化服务保持，不重复迁移。
            RunRecord waiting = persistenceService.waitForApproval(
                    principal, runningRun, context.authorizedTools(), runtimeResult.toolCalls());
            AgentRunResult waitingResult = new AgentRunResult(
                    waiting.id(), waiting.status(), "", List.of(), waiting.startedAt(), null, "");
            return new AgentRuntimeResult(waitingResult, null, runtimeEnvelope.pendingApproval());
        }
        var completedRun = persistenceService.complete(
                principal, runningRun, runtimeResult, context.authorizedTools()
        );
        return new AgentRuntimeResult(
                responseWithPersistentId(completedRun, runtimeResult),
                redactMessage(runtimeEnvelope.assistantMessage()));
    }

    private ResolvedRunContext resolve(PrincipalRef principal, UUID agentId) {
        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (!agent.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 已禁用");
        }
        ModelConfig modelConfig = modelConfigRepository
                .findByTenantAndId(principal.tenantId(), agent.modelProviderId())
                .filter(ModelConfig::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型配置不可用"));
        return new ResolvedRunContext(agent, modelConfig, authorizedTools(principal, agent));
    }

    /**
     * 在离开运行编排边界前再次脱敏最终消息。
     *
     * <p>适配器本应只产生受治理摘要，但运行服务仍执行纵深防御，确保旧 Runtime 或未来适配器不会将
     * 敏感文本通过会话持久化或 {@code completed} SSE 事件泄露出去；工具调用标识、名称与终态保持原样，
     * 以维持内容块关联关系。</p>
     */
    private AgentMessageSnapshot redactMessage(AgentMessageSnapshot message) {
        if (message == null) {
            return null;
        }
        List<MessageContentBlock> blocks = message.contentBlocks().stream()
                .map(block -> new MessageContentBlock(
                        block.type(), redactMessageBlockText(block), block.toolCallId(),
                        block.toolName(), block.status(), block.durationMillis()))
                .toList();
        return new AgentMessageSnapshot(message.replyId(), message.senderName(), blocks);
    }

    /**
     * 对 Runtime 进度执行纵深脱敏和限长，避免完整工具载荷直接进入浏览器。
     *
     * @param progress 适配器产生的受控进度
     * @return 可发送给 SSE 客户端的进度
     */
    private AgentProgressEvent redactProgress(AgentProgressEvent progress) {
        Objects.requireNonNull(progress, "progress 不能为空");
        return new AgentProgressEvent(
                progress.type(), progress.replyId(), progress.blockId(), progress.toolCallId(),
                progress.toolName(), redactTracePayload(progress.content()), redactTracePayload(progress.input()),
                redactTracePayload(progress.output()), progress.status(), progress.durationMillis());
    }

    /**
     * 对会话执行轨迹中的思考、工具入参和返回值进行脱敏与长度限制。
     *
     * @param block 当前消息内容块
     * @return 可安全持久化和发送到会话页面的文本
     */
    private String redactMessageBlockText(MessageContentBlock block) {
        return switch (block.type()) {
            case THINKING, TOOL_USE, TOOL_RESULT -> redactTracePayload(block.text());
            case TEXT -> redactor.redact(block.text());
        };
    }

    /**
     * 先脱敏再截断可展示载荷，避免截断破坏 Secret、URL 或令牌的识别模式。
     *
     * @param value 待处理的思考、工具入参、返回值或错误文本
     * @return 脱敏且不超过页面展示上限的文本
     */
    private String redactTracePayload(String value) {
        String redacted = redactor.redact(value);
        if (redacted.length() <= EXECUTION_TRACE_PAYLOAD_MAX_CHARACTERS) {
            return redacted;
        }
        return redacted.substring(0, EXECUTION_TRACE_PAYLOAD_MAX_CHARACTERS) + "\n…（内容超过展示上限，已截断）";
    }

    private record ResolvedRunContext(
            AgentDefinition agent,
            ModelConfig modelConfig,
            List<ToolDefinition> authorizedTools
    ) {
        private ResolvedRunContext {
            authorizedTools = List.copyOf(authorizedTools);
        }
    }

    /**
     * 尽最大努力将异常运行收口为失败状态。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param runningRun 已经持久化且状态为 RUNNING 的记录。
     */
    private void bestEffortFailureClosure(PrincipalRef principal, RunRecord runningRun) {
        try {
            persistenceService.completeFailure(principal, runningRun);
        } catch (RuntimeException failureClosureFailure) {
            log.warn("运行失败收口未完成。runId={}, reason={}",
                    runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
        }
    }

    /**
     * 尽最大努力追加运行失败审计。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param runningRun 已经持久化且状态为 RUNNING 的记录。
     */
    private void bestEffortFailureAudit(PrincipalRef principal, RunRecord runningRun) {
        persistenceService.appendFailureAudit(principal, runningRun);
    }

    /**
     * 筛选当前主体获准在本次运行中使用的工具。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param agent 当前处理的 Agent 定义。
     */
    private List<ToolDefinition> authorizedTools(PrincipalRef principal, AgentDefinition agent) {
        List<ToolGrant> grants = grantRepository.listByTenantAndAgent(principal.tenantId(), agent.id());
        Map<UUID, ToolDefinition> tools = new LinkedHashMap<>();
        for (ToolGrant grant : grants) {
            if (!grant.granted() || !principal.tenantId().equals(grant.tenantId())) {
                continue;
            }
            toolRepository.findByTenantAndId(principal.tenantId(), grant.toolId())
                    .ifPresent(tool -> {
                        AuthorizationDecision decision = toolAuthorizationPolicy.check(principal, agent.id(), tool, grants);
                        if (decision.allowed()) {
                            tools.putIfAbsent(tool.id(), tool);
                        }
                    });
        }
        return new ArrayList<>(tools.values());
    }

    /**
     * 使用已持久化的运行 ID 重建响应。
     *
     * @param completedRun 已进入终态、等待持久化的运行记录
     * @param result 上一步得到的处理结果。
     */
    private AgentRunResult responseWithPersistentId(com.cmagent.core.domain.RunRecord completedRun, AgentRunResult result) {
        List<ToolCallRecord> toolCalls = result.toolCalls() == null
                ? List.of()
                : result.toolCalls().stream().map(this::redactToolCall).toList();
        return new AgentRunResult(
                completedRun.id(), completedRun.status(), redactor.redact(completedRun.output()), toolCalls,
                completedRun.startedAt(), completedRun.finishedAt(), redactor.redact(completedRun.errorMessage())
        );
    }

    /**
     * 脱敏工具调用记录中的输入、输出和错误信息。
     *
     * @param record 当前处理的运行或工具调用记录
     */
    private ToolCallRecord redactToolCall(ToolCallRecord record) {
        return new ToolCallRecord(
                record.toolId(), record.toolCallId(), record.toolName(), redactTracePayload(record.inputSummary()),
                redactTracePayload(record.outputSummary()), record.status(), record.duration(),
                record.authorized(), redactTracePayload(record.errorMessage())
        );
    }

    /**
     * 表示 {@code RuntimeExecutionException} 对应失败场景的受控异常。
     */
    public static final class RuntimeExecutionException extends RuntimeException {
        /**
         * 表示 {@code RuntimeExecutionException} 对应失败场景的受控异常。
         *
         * @param cause 触发当前失败的原始异常。
         */
        public RuntimeExecutionException(Throwable cause) {
            super(CONTROLLED_FAILURE, cause);
        }
    }
}
