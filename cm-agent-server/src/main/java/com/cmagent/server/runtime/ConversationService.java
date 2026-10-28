package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.ConversationRunResult;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.cmagent.core.repository.ConversationRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 编排会话、消息、Run 与 Runtime 的分段提交边界。
 *
 * <p>USER 消息和 {@code RUNNING} Run 在短事务中共同提交，随后才调用模型；模型调用绝不持有数据库事务。
 * 最终 Run 与 assistant/待审批快照在另一个短事务共同提交；停止与完成在单实例共享运行锁内竞争。
 * 模型或基础设施失败不会伪造 assistant 回复，已成功提交的 USER 消息仍保留。</p>
 */
@Service
public class ConversationService {
    /** 仅Spring完整装配开启新来源闸门；旧手工装配仍被创建入口限制为旧模式。 */
    private WorkspaceImportAccess importAccess;
    @org.springframework.beans.factory.annotation.Autowired
    void configureImportAccess(WorkspaceImportAccess access) { this.importAccess = access; }
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final AgentDefinitionRepository agentRepository;
    private final RunRepository runRepository;
    private final RunPersistenceService runPersistenceService;
    private final RunExecutionService executionService;
    private final ToolApprovalService approvalService;
    private final ConversationPromptComposer promptComposer;
    /** 与首发共用控制锁和 JDBC 行锁，绑定更新不能穿过消息创建边界。 */
    private WorkspaceSelectionService workspaceSelections;

    @org.springframework.beans.factory.annotation.Autowired
    void configureWorkspaceSelections(WorkspaceSelectionService service) { this.workspaceSelections = service; }

    private final SensitiveDataRedactor redactor;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建会话编排服务。
     *
     * @param conversationRepository 会话元数据仓储
     * @param messageRepository 会话消息仓储
     * @param agentRepository 用于在可信租户内校验 Agent
     * @param runRepository 查询历史消息关联 Run 的终态
     * @param runPersistenceService 创建并收口运行记录
     * @param executionService 调用受治理 Runtime
     * @param approvalService 阻止同会话并发消息并保存 Runtime ASK 审批
     * @param promptComposer 构造受限历史上下文
     * @param redactor 在持久化和模型调用前清理文本
     * @param transactionTemplate JDBC 模式的短事务模板；内存模式可为空
     */
    public ConversationService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            AgentDefinitionRepository agentRepository,
            RunRepository runRepository,
            RunPersistenceService runPersistenceService,
            RunExecutionService executionService,
            ToolApprovalService approvalService,
            ConversationPromptComposer promptComposer,
            SensitiveDataRedactor redactor,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.runPersistenceService = runPersistenceService;
        this.executionService = executionService;
        this.approvalService = approvalService;
        this.promptComposer = promptComposer;
        this.redactor = redactor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 为当前主体在指定 Agent 下创建空会话。
     *
     * @param principal 已认证主体，租户只能从该可信上下文取得
     * @param agentId 目标 Agent 标识
     * @return 使用默认标题的新会话
     * @throws ResponseStatusException Agent 不属于当前租户或不存在时抛出 404
     */
    public Conversation create(PrincipalRef principal, UUID agentId) {
        return create(principal, agentId, null);
    }

    /**
     * 创建会话并原子保存选择；无请求体保持 DEFAULT。正文读取不在此短事务内执行。
     * @param principal 可信认证主体
     * @param agentId 当前租户 Agent
     * @param selection 可省略的逻辑目录选择
     * @return 新空会话
     */
    public Conversation create(PrincipalRef principal, UUID agentId, com.cmagent.core.domain.WorkspaceSelection selection) {
        // 手工装配旧服务只兼容 DEFAULT，不能静默忽略调用方显式选择的资料边界。
        if (workspaceSelections == null && selection != null && selection.mode() != com.cmagent.core.domain.WorkspaceSelection.Mode.DEFAULT)
            throw new IllegalStateException("当前服务未装配工作区选择能力");
        requireAgent(principal, agentId);
        return inTransaction(() -> {
            Instant now = Instant.now();
            UUID id = UUID.randomUUID();
            var binding = workspaceSelections == null ? null : workspaceSelections.newConversation(principal, agentId, id, selection);
            if (binding != null) workspaceSelections.saveNewConversation(binding);
            try {
                var created=new Conversation(id,principal.tenantId(),agentId,Conversation.DEFAULT_TITLE,principal.principalId(),now,now);
                return binding!=null && binding.selection().mode()==com.cmagent.core.domain.WorkspaceSelection.Mode.IMPORTED
                        ? conversationRepository.saveWithWorkspaceProtocol(principal.tenantId(),created,3)
                        : conversationRepository.save(principal.tenantId(),created);
            } catch (RuntimeException failure) {
                // memory 没有数据库回滚，预存选择失败不能遗留半个会话；补偿失败保留原故障为 suppressed。
                try { if (binding != null) workspaceSelections.abandonNewConversation(binding); }
                catch (RuntimeException cleanup) { cleanup.addSuppressed(failure); throw cleanup; }
                throw failure;
            }
        });
    }

    /**
     * 条件改绑与首发使用相同锁顺序；跨实例由 Repository 行锁串行化，事务不包含文件或模型调用。
     * @param principal 可信主体
     * @param agentId 当前 Agent
     * @param conversationId 会话标识
     * @param selection 显式新选择
     * @param expectedVersion 最后读取的版本
     * @return 保存后的安全视图
     */
    public WorkspaceSelectionService.View updateWorkspace(PrincipalRef principal, UUID agentId, UUID conversationId,
            com.cmagent.core.domain.WorkspaceSelection selection, long expectedVersion) {
        return executionService.controls().withConversation(conversationId, () -> inTransaction(() ->
                workspaceSelections.update(principal, get(principal, agentId, conversationId), selection, expectedVersion)));
    }

    /**
     * 读取当前租户、指定 Agent 下的会话。
     *
     * <p>跨租户和跨 Agent 均统一返回 404，避免泄露会话是否存在。</p>
     */
    public Conversation get(PrincipalRef principal, UUID agentId, UUID conversationId) {
        var conversation = conversationRepository.findByTenantAndAgentAndId(
                        principal.tenantId(), agentId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (importAccess != null) importAccess.conversation(principal, conversation);
        return conversation;
    }

    /**
     * 按稳定复合游标列出当前主体可见的 Agent 会话。
     */
    public List<Conversation> list(
            PrincipalRef principal, UUID agentId, ConversationPageRequest pageRequest) {
        requireAgent(principal, agentId);
        return importAccess == null ? conversationRepository.listByTenantAndAgent(principal.tenantId(), agentId, pageRequest)
                : conversationRepository.listVisible(principal.tenantId(), agentId, principal.principalId(), pageRequest);
    }

    /**
     * 在读取消息前校验会话归属，防止消息 Repository 仅按会话标识查询时绕过 Agent 边界。
     */
    public List<ConversationMessage> messages(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            MessagePageRequest pageRequest
    ) {
        get(principal, agentId, conversationId);
        var page = messageRepository.list(principal.tenantId(), conversationId, pageRequest);
        // 也核对固定Run来源，避免历史不一致数据经消息入口旁路私有运行。
        if (importAccess != null) for (var message : page) if (message.runId() != null) importAccess.run(principal, agentId, message.runId());
        return page;
    }

    /**
     * 追加用户消息、执行本轮 Runtime，并在收到最终安全快照时追加 assistant 消息。
     *
     * <p>启动回调只会在 USER 消息和 {@code RUNNING} Run 已成功提交后触发，因此 SSE 客户端可安全回放其
     * 关联标识。当前 USER 消息不会进入本轮历史，避免输入重复；历史关联失败 Run 会传给提示词编排器作
     * 显式标记。Runtime 的异常由既有运行服务负责诊断和失败收口，本方法不会补写虚假的 assistant 消息。</p>
     *
     * @param principal 已认证主体，提供唯一可信 tenant
     * @param agentId 当前会话绑定的 Agent 标识
     * @param conversationId 目标会话标识
     * @param input 原始用户输入
     * @param startedConsumer USER 消息与 Run 提交后的通知消费者
     * @param deltaConsumer Runtime 输出已脱敏文本增量的消费者
     * @return 用户消息、运行结果与可选 assistant 消息的关联结果
     */
    public ConversationRunResult send(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            Consumer<ConversationRunStarted> startedConsumer,
            Consumer<AgentTextDelta> deltaConsumer
    ) {
        return send(principal, agentId, conversationId, input, startedConsumer, deltaConsumer, ignored -> {
        });
    }

    /**
     * 追加用户消息、执行本轮 Runtime，并向当前观察者发送受控思考与工具进度。
     *
     * <p>进度事件不会参与消息序号分配；只有 Runtime 最终安全快照会随 assistant 消息持久化，
     * 因此浏览器断开或临时事件丢失不会改变会话历史的权威结果。</p>
     *
     * @param principal 已认证主体，提供唯一可信 tenant
     * @param agentId 当前会话绑定的 Agent 标识
     * @param conversationId 目标会话标识
     * @param input 原始用户输入
     * @param startedConsumer USER 消息与 Run 提交后的通知消费者
     * @param deltaConsumer Runtime 输出已脱敏文本增量的消费者
     * @param progressConsumer Runtime 输出已脱敏执行进度的消费者
     * @return 用户消息、运行结果与可选 assistant 消息的关联结果
     */
    public ConversationRunResult send(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            Consumer<ConversationRunStarted> startedConsumer,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Conversation conversation = get(principal, agentId, conversationId);
        approvalService.requireNoPending(principal, agentId, conversationId);
        String safeInput = redactor.redact(input);
        UUID runId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        StartedState started = executionService.controls().withConversation(conversationId, () -> inTransaction(() -> {
            // JDBC FOR UPDATE 与改绑共用同一行；不能只依赖单进程控制锁。
            conversationRepository.lock(principal.tenantId(), agentId, conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
            if (activeRun(principal, agentId, conversationId) != null) {
                throw new RunControlException(com.cmagent.api.ApiErrorCode.RUN_CONTROL_CONFLICT,
                        "当前会话仍有未结束运行，请先停止或核对运行状态");
            }
            var workspace = workspaceSelections == null ? null : workspaceSelections.forConversation(principal, conversation, runId);
            RunRecord run = workspace!=null && workspace.selection().mode()==com.cmagent.core.domain.WorkspaceSelection.Mode.IMPORTED
                    ? runPersistenceService.start(principal,agentId,safeInput,runId,com.cmagent.core.domain.RunKind.NORMAL,3)
                    : runPersistenceService.start(principal,agentId,safeInput,runId);
            try {
            if (workspace != null) workspaceSelections.saveRun(workspace);
            ConversationMessage userMessage = messageRepository.append(principal.tenantId(),
                    new ConversationMessageDraft(
                            userMessageId, principal.tenantId(), conversationId, MessageRole.USER,
                            principal.displayName(), List.of(MessageContentBlock.text(safeInput)), runId, Instant.now()));
            if (Conversation.DEFAULT_TITLE.equals(conversation.title())) {
                conversationRepository.touch(
                        principal.tenantId(), conversationId, titleFrom(safeInput), userMessage.createdAt());
            }
            return new StartedState(run, userMessage);
            } catch (RuntimeException failure) {
                // JDBC 由短事务回滚，memory 则显式关闭已创建的 Run，不能遗留永远活动的半次首发。
                if (transactionTemplate == null) {
                    try { runPersistenceService.completeFailure(principal, run); }
                    catch (RuntimeException cleanup) { cleanup.addSuppressed(failure); throw cleanup; }
                }
                throw failure;
            }
        }));
        var execution = executionService.controls().register(started.run(), conversationId);
        try {
        startedConsumer.accept(new ConversationRunStarted(
                conversationId, runId, started.userMessage().id()));

        List<ConversationMessage> recent = messageRepository.listRecent(
                principal.tenantId(), conversationId, ConversationPromptComposer.MAX_HISTORY_MESSAGES + 1);
        List<ConversationMessage> history = recent.stream()
                .filter(message -> message.sequence() < started.userMessage().sequence())
                .toList();
        Set<UUID> failedRunIds = history.stream()
                .map(ConversationMessage::runId)
                .filter(Objects::nonNull)
                .distinct()
                .filter(historyRunId -> runRepository.findByTenantAndAgentAndId(
                                principal.tenantId(), agentId, historyRunId)
                        .map(run -> run.status() == RunStatus.FAILED)
                        .orElse(false))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> stoppedRunIds = history.stream().map(ConversationMessage::runId).filter(Objects::nonNull)
                .filter(id -> runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, id)
                        .map(run -> run.status() == RunStatus.CANCELLED).orElse(false))
                .collect(java.util.stream.Collectors.toSet());
        String runtimeInput = promptComposer.compose(history, safeInput, failedRunIds, stoppedRunIds);
        java.util.concurrent.atomic.AtomicReference<ConversationMessage> assistant = new java.util.concurrent.atomic.AtomicReference<>();
        AgentRuntimeResult runtimeResult = executionService.runPreparedAndFinalize(
                principal, agentId, started.run(), runtimeInput, conversationId, null, deltaConsumer, progressConsumer,
                finish -> inTransaction(() -> {
                    AgentRuntimeResult result = finish.get();
                    if (result.run().status() == RunStatus.CANCELLED) {
                        approvalService.cancelWaiting(principal, started.run(), conversationId);
                    }
                    if (result.run().status() == RunStatus.WAITING_APPROVAL) {
                        approvalService.create(principal, agentId, conversationId, runId, result.pendingApproval());
                    }
                    if (result.assistantMessage() != null) {
                        assistant.set(messageRepository.append(principal.tenantId(), new ConversationMessageDraft(
                    UUID.randomUUID(), principal.tenantId(), conversationId, MessageRole.ASSISTANT,
                                result.assistantMessage().senderName(), result.assistantMessage().contentBlocks(),
                                runId, Instant.now())));
                    }
                    return result;
                }));
        ConversationMessage assistantMessage = assistant.get();
        return new ConversationRunResult(
                conversationId,
                userMessageId,
                assistantMessage == null ? null : assistantMessage.id(),
                runtimeResult.run(),
                assistantMessage);
        } catch (RuntimeException failure) {
            // 包含 Agent 停用等 Runtime 入场前失败；只关闭仍活动的本轮，保留原异常和严格审计失败语义。
            try {
                // ASK 的 Runtime 已保存检查点；审批创建或通知失败也必须清理，不能只更新 Run 终态。
                approvalService.closeFailedRun(principal, agentId, runId);
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
                throw cleanupFailure;
            }
            throw failure;
        } finally {
            executionService.controls().release(runId, execution);
        }
    }

    /** 查询会话内权威 Run；租户和 Agent 必须来自已认证上下文及已验证路由。 */
    public RunRecord run(PrincipalRef principal, UUID agentId, UUID conversationId, UUID runId) {
        get(principal, agentId, conversationId);
        if (messageRepository.listByRun(principal.tenantId(), conversationId, runId).stream()
                .noneMatch(message -> message.role() == MessageRole.USER)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在或不属于当前会话");
        }
        var run = runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在"));
        if (importAccess != null) importAccess.run(principal, run);
        return run;
    }

    /** 返回活动 Run，包含停止中及不可达执行句柄；查询不能自动重放或收口运行。 */
    public RunRecord activeRun(PrincipalRef principal, UUID agentId, UUID conversationId) {
        get(principal, agentId, conversationId);
        return messageRepository.activeRunCandidates(principal.tenantId(), conversationId).stream()
                .map(id -> runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, id))
                .flatMap(java.util.Optional::stream).filter(run -> run.status().isActive())
                .peek(run -> { if (importAccess != null) importAccess.run(principal, run); })
                .sorted(java.util.Comparator.comparing(RunRecord::startedAt).reversed()).findFirst().orElse(null);
    }

    /** 消息必须来自当前会话仓储页；只返回关联 Run 状态，不向前端暴露额外主体或运行输入。 */
    public java.util.Map<UUID, RunStatus> runStatuses(PrincipalRef principal, UUID agentId, List<ConversationMessage> messages) {
        java.util.Map<UUID, RunStatus> statuses = new java.util.LinkedHashMap<>();
        for (ConversationMessage message : messages) {
            if (!principal.tenantId().equals(message.tenantId())) throw new IllegalArgumentException("消息租户不一致");
            if (message.runId() != null && !statuses.containsKey(message.runId())) {
                runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, message.runId())
                        .ifPresent(run -> { if (importAccess != null) importAccess.run(principal, run); statuses.put(run.id(), run.status()); });
            }
        }
        return java.util.Map.copyOf(statuses);
    }

    /**
     * 停止当前主体发起的会话 Run。锁只覆盖状态及审计短事务，不等待模型或外部调用。
     * 执行句柄不可达时明确拒绝，避免把重启遗留或其他实例执行误报为停止成功。
     */
    public RunRecord cancel(PrincipalRef principal, UUID agentId, UUID conversationId, UUID runId) {
        return executionService.controls().withRun(runId, () -> {
            RunRecord current = run(principal, agentId, conversationId, runId);
            if (!current.principalId().equals(principal.principalId())) {
                runPersistenceService.auditStop(principal, current, "DENIED", "仅允许本轮发起人停止");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅允许本轮发起人停止");
            }
            if (!current.status().isActive()) return current;
            var execution = executionService.controls().find(runId);
            if (current.status() == RunStatus.WAITING_APPROVAL && execution == null) {
                return inTransaction(() -> {
                    RunRecord stopping = runPersistenceService.requestCancellation(principal, current);
                    approvalService.cancelWaiting(principal, stopping, conversationId);
                    return runPersistenceService.complete(principal, stopping, new com.cmagent.core.domain.AgentRunResult(
                            runId, RunStatus.CANCELLED, "", List.of(), current.startedAt(), Instant.now(), ""), List.of());
                });
            }
            if (execution == null || !execution.belongsTo(current, conversationId)) {
                throw new RunControlException(com.cmagent.api.ApiErrorCode.RUN_CONTROL_UNAVAILABLE,
                        "本轮执行句柄不可达，请核对服务实例或联系管理员处理遗留运行");
            }
            if (!executionService.supportsCancellation()) {
                throw new RunControlException(com.cmagent.api.ApiErrorCode.RUN_CONTROL_UNAVAILABLE,
                        "当前运行时不支持停止，请等待本轮结束");
            }
            if (current.status() == RunStatus.CANCELLING) return current;
            RunRecord stopping = runPersistenceService.requestCancellation(principal, current);
            execution.cancellation().request();
            return stopping;
        });
    }

    /**
     * 同步发送便利入口，不订阅启动或增量事件。
     */
    public ConversationRunResult send(
            PrincipalRef principal, UUID agentId, UUID conversationId, String input) {
        return send(principal, agentId, conversationId, input, ignored -> {
        }, ignored -> {
        });
    }

    /** 在执行创建或列表前验证 Agent 属于当前可信租户。 */
    private void requireAgent(PrincipalRef principal, UUID agentId) {
        agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    /**
     * 在 JDBC 模式中执行短事务；内存模式没有事务模板时直接执行。
     *
     * <p>调用方不得把 Runtime 或外部网络操作传入此方法，避免长时间占用数据库锁。</p>
     */
    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        if (transactionTemplate == null) {
            return operation.get();
        }
        return Objects.requireNonNull(transactionTemplate.execute(status -> operation.get()), "事务结果不能为空");
    }

    /**
     * 从首条用户输入生成标题，并按 Unicode code point 截断，避免截断代理对。
     */
    private static String titleFrom(String input) {
        String normalized = input.strip().replaceAll("\\s+", " ");
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 40) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, 40));
    }

    /**
     * 启动短事务的结果；两项在提交后才用于发布 SSE。
     * @param run 已保存的当前 Run
     * @param userMessage 与 Run 关联的已保存用户消息
     */
    private record StartedState(RunRecord run, ConversationMessage userMessage) {
    }

    public record ConversationRunStarted(UUID conversationId, UUID runId, UUID userMessageId) {
    }
}
