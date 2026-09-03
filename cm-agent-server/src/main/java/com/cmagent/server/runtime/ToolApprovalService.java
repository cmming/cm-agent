package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RuntimePendingApproval;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalItem;
import com.cmagent.core.domain.ToolApprovalPolicy;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolApprovalRepository;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 编排审批创建、原子决定、Run 恢复、会话消息和安全视图。 */
@Service
public class ToolApprovalService {
    private static final int PENDING_LIMIT = 50;
    private static final long POLICY_VERSION = 1L;

    private final ToolApprovalRepository approvalRepository;
    private final RunRepository runRepository;
    private final ConversationMessageRepository messageRepository;
    private final RuntimeCheckpointRepository checkpointRepository;
    private final RunPersistenceService runPersistenceService;
    private final RunExecutionService runExecutionService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;
    private final AgentScopeRuntimeProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建审批编排服务。
     *
     * <p>tenant、发起主体和审批主体只能来自调用方传入的已认证 {@link PrincipalRef}；
     * 客户端提交内容只能包含公开 itemId 与决定。JDBC 模式下创建/决定/过期及对应严格审计
     * 共享短事务，模型恢复不占用数据库事务。</p>
     *
     * @param approvalRepository 审批请求及明细仓储
     * @param runRepository 待读取和收口的运行仓储
     * @param messageRepository 恢复成功后的 assistant 消息仓储
     * @param checkpointRepository 加密 AgentScope 状态仓储
     * @param runPersistenceService Run 状态和严格审计编排
     * @param runExecutionService 使用原运行上下文恢复 Runtime
     * @param permissionEvaluator 计算前端只读或可决定状态
     * @param auditAppender 审批生命周期严格审计入口
     * @param redactor 审批展示摘要脱敏器
     * @param properties 审批有效期配置
     * @param transactionTemplate JDBC 短事务模板；memory 模式可以为空
     */
    public ToolApprovalService(
            ToolApprovalRepository approvalRepository,
            RunRepository runRepository,
            ConversationMessageRepository messageRepository,
            RuntimeCheckpointRepository checkpointRepository,
            RunPersistenceService runPersistenceService,
            RunExecutionService runExecutionService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor,
            AgentScopeRuntimeProperties properties,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.approvalRepository = approvalRepository;
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.checkpointRepository = checkpointRepository;
        this.runPersistenceService = runPersistenceService;
        this.runExecutionService = runExecutionService;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.redactor = redactor;
        this.properties = properties;
        this.clock = Clock.systemUTC();
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 将 Runtime ASK 安全快照保存为请求和明细。
     *
     * <p>原始 ToolUseBlock 只存在于加密 AgentState Store；审批表仅保存脱敏摘要与输入哈希。
     * checkpointRef 使用服务端构造的 runId 状态槽，前端视图永不返回该字段。</p>
     */
    public ToolApprovalView create(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            UUID runId,
            RuntimePendingApproval pending
    ) {
        Instant now = clock.instant();
        UUID approvalId = UUID.randomUUID();
        List<ToolApprovalItem> items = pending.items().stream().map(item -> new ToolApprovalItem(
                UUID.randomUUID(), approvalId, item.toolCallId(), item.toolId(), item.toolName(), item.riskLevel(),
                redactor.redact(item.inputSummary()), item.inputHash(), ToolApprovalPolicy.EACH_CALL,
                POLICY_VERSION, null)).toList();
        ToolApprovalRequest saved = inTransaction(() -> {
            ToolApprovalRequest persisted = approvalRepository.save(new ToolApprovalRequest(
                    approvalId, principal.tenantId(), agentId, conversationId, runId,
                    principal.principalId(), principal.displayName(), ToolApprovalStatus.PENDING,
                    now.plus(properties.getApprovalTtl()), 0, runId.toString(), now, now,
                    null, null, null, items));
            // 审批事实与严格审计必须同成同败；否则可能出现前端可决定但审计链缺口。
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_APPROVAL_CREATE",
                    "TOOL_APPROVAL", approvalId.toString(), "PENDING", "已创建高风险工具审批请求");
            return persisted;
        });
        return view(principal, saved);
    }

    /** 列出当前会话未过期 PENDING 请求。 */
    public List<ToolApprovalView> listPending(PrincipalRef principal, UUID agentId, UUID conversationId) {
        return approvalRepository.listPending(
                        principal.tenantId(), agentId, conversationId, clock.instant(), PENDING_LIMIT)
                .stream().map(request -> view(principal, request)).toList();
    }

    /** 查询一个审批的服务端权威状态。 */
    public ToolApprovalView get(
            PrincipalRef principal, UUID agentId, UUID conversationId, UUID approvalId) {
        ToolApprovalRequest request = require(principal, agentId, conversationId, approvalId);
        return view(principal, request);
    }

    /**
     * 返回已处理审批的只读安全快照；调用方需先校验会话读取权限和资源归属。
     *
     * <p>复用既有视图转换，不暴露输入哈希、原主体权限或加密检查点，也不恢复运行。
     * 仓储仅返回终态，因而视图的 {@code canDecide} 始终为 false。</p>
     *
     * @param principal 当前已认证主体，租户只取该可信上下文
     * @param agentId 已通过归属校验的 Agent
     * @param conversationId 已通过归属校验的会话
     * @param page 已校验的历史分页条件
     * @return 本页历史审批的脱敏视图
     */
    public List<ToolApprovalView> listHistory(
            PrincipalRef principal, UUID agentId, UUID conversationId, ToolApprovalHistoryPageRequest page) {
        return approvalRepository.listHistory(principal.tenantId(), agentId, conversationId, page)
                .stream().map(request -> view(principal, request)).toList();
    }

    /** 当前会话有 PENDING 审批时禁止再启动一个 Run，避免同一会话状态交叉。 */
    public void requireNoPending(PrincipalRef principal, UUID agentId, UUID conversationId) {
        if (approvalRepository.hasPending(
                principal.tenantId(), agentId, conversationId, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话存在待审批操作，请先完成审批");
        }
    }

    /**
     * 原子接受全量决定并恢复原 Run。
     *
     * <p>第一版只开放发起人自批，数据模型仍分别保存 requestedBy 和 decidedBy。工具执行继续使用
     * 当前重新认证的原发起主体，避免把未来独立审批人的权限借给执行链。</p>
     */
    public ApprovalResumeOutcome decideAndResume(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            List<ItemDecision> submitted,
            Consumer<ToolApprovalView> decisionConsumer,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        ToolApprovalRequest current = require(principal, agentId, conversationId, approvalId);
        Instant now = clock.instant();
        if (current.status() != ToolApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批请求已处理，请刷新后查看最新状态");
        }
        if (current.isExpired(now)) {
            expire(principal, current, now);
            throw new ResponseStatusException(HttpStatus.GONE, "审批请求已过期");
        }
        if (!current.requestedBy().equals(principal.principalId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "第一版仅允许运行发起人处理审批");
        }
        Map<UUID, ToolApprovalDecision> decisions = validateDecisions(current, submitted);
        ToolApprovalStatus status = aggregate(decisions.values());
        boolean accepted = inTransaction(() -> {
            boolean decided = approvalRepository.decide(
                    principal.tenantId(), agentId, conversationId, approvalId, expectedVersion, status,
                    decisions, principal.principalId(), principal.displayName(), now);
            if (decided) {
                // 决定和审计先在短事务中提交，Runtime 恢复始终位于数据库事务之外。
                auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_APPROVAL_DECIDE",
                        "TOOL_APPROVAL", approvalId.toString(), status.name(), "高风险工具审批决定已提交");
            }
            return decided;
        });
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批状态已变化，请刷新后重试");
        }
        ToolApprovalRequest decided = require(principal, agentId, conversationId, approvalId);
        decisionConsumer.accept(view(principal, decided));

        RunRecord waitingRun = runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, current.runId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在"));
        AgentRuntimeResult runtimeResult;
        if (status == ToolApprovalStatus.DENIED) {
            AgentRunResult denied = new AgentRunResult(
                    waitingRun.id(), RunStatus.DENIED, "", List.of(), waitingRun.startedAt(), now, "工具调用已被用户拒绝");
            RunRecord completed = runPersistenceService.complete(principal, waitingRun, denied, List.of());
            runtimeResult = new AgentRuntimeResult(new AgentRunResult(
                    completed.id(), completed.status(), completed.output(), List.of(), completed.startedAt(),
                    completed.finishedAt(), completed.errorMessage()), null);
            deleteCheckpoint(principal, waitingRun);
        } else {
            List<RuntimeApprovalDecisions.Item> runtimeDecisions = current.items().stream()
                    .map(item -> new RuntimeApprovalDecisions.Item(
                            item.toolCallId(), item.toolId(), item.inputHash(), decisions.get(item.id())))
                    .toList();
            try {
                runtimeResult = runExecutionService.resumePrepared(
                        principal, agentId, waitingRun, conversationId, new RuntimeApprovalDecisions(runtimeDecisions),
                        deltaConsumer, progressConsumer);
            } catch (RuntimeException failure) {
                // Agent/模型停用等前置失败可能发生在 Runtime 自身的收口边界之前，审批既已接受，
                // 此时必须清除状态槽并关闭仍活动的 Run，不能留下可误重放的待调用工具。
                try {
                    runRepository.findByTenantAndAgentAndId(principal.tenantId(), agentId, waitingRun.id())
                            .filter(run -> run.status().isActive())
                            .ifPresent(run -> runPersistenceService.completeFailure(principal, run));
                    deleteCheckpoint(principal, waitingRun);
                    auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_APPROVAL_RESUME",
                            "TOOL_APPROVAL", approvalId.toString(), "FAILED", "审批已接受但运行恢复失败");
                } catch (RuntimeException cleanupFailure) {
                    // 审计或持久化失败仍向统一边界传播，原恢复原因作为 suppressed 保留。
                    if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
                    throw cleanupFailure;
                }
                throw failure;
            }
        }

        ConversationMessage assistant = null;
        ToolApprovalView nextApproval = null;
        if (runtimeResult.run().status() == RunStatus.WAITING_APPROVAL) {
            nextApproval = create(principal, agentId, conversationId, waitingRun.id(), runtimeResult.pendingApproval());
        } else if (runtimeResult.assistantMessage() != null) {
            assistant = messageRepository.append(principal.tenantId(), new ConversationMessageDraft(
                    UUID.randomUUID(), principal.tenantId(), conversationId, MessageRole.ASSISTANT,
                    runtimeResult.assistantMessage().senderName(), runtimeResult.assistantMessage().contentBlocks(),
                    waitingRun.id(), now));
        }
        return new ApprovalResumeOutcome(view(principal, decided), runtimeResult.run(), assistant, nextApproval);
    }

    /** 在建立 SSE 前完成权限之外的资源、状态、版本、过期和决定完整性校验。 */
    public void validateForSubmission(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            List<ItemDecision> submitted
    ) {
        ToolApprovalRequest current = require(principal, agentId, conversationId, approvalId);
        if (current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批状态已变化，请刷新后重试");
        }
        if (current.isExpired(clock.instant())) {
            expire(principal, current, clock.instant());
            throw new ResponseStatusException(HttpStatus.GONE, "审批请求已过期");
        }
        if (!current.requestedBy().equals(principal.principalId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "第一版仅允许运行发起人处理审批");
        }
        validateDecisions(current, submitted);
    }

    private ToolApprovalRequest require(
            PrincipalRef principal, UUID agentId, UUID conversationId, UUID approvalId) {
        return approvalRepository.find(principal.tenantId(), agentId, conversationId, approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批请求不存在"));
    }

    private Map<UUID, ToolApprovalDecision> validateDecisions(
            ToolApprovalRequest request, List<ItemDecision> submitted) {
        if (submitted == null || submitted.size() != request.items().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须为全部待审批工具选择允许或拒绝");
        }
        Set<UUID> expected = request.items().stream().map(ToolApprovalItem::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, ToolApprovalDecision> decisions = new LinkedHashMap<>();
        for (ItemDecision item : submitted) {
            if (item == null || item.itemId() == null || item.decision() == null
                    || !expected.contains(item.itemId()) || decisions.put(item.itemId(), item.decision()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批明细不完整或不属于当前请求");
            }
        }
        return Map.copyOf(decisions);
    }

    private static ToolApprovalStatus aggregate(java.util.Collection<ToolApprovalDecision> decisions) {
        boolean approved = decisions.contains(ToolApprovalDecision.APPROVE);
        boolean denied = decisions.contains(ToolApprovalDecision.DENY);
        return approved && denied ? ToolApprovalStatus.PARTIALLY_APPROVED
                : approved ? ToolApprovalStatus.APPROVED : ToolApprovalStatus.DENIED;
    }

    /** 原子标记审批过期，并在本实例赢得更新时收口 Run 和加密检查点。 */
    private void expire(PrincipalRef principal, ToolApprovalRequest request, Instant now) {
        inTransaction(() -> {
            if (!approvalRepository.expire(
                    principal.tenantId(), request.agentId(), request.conversationId(), request.id(),
                    request.version(), principal.principalId(), now)) {
                return false;
            }
            runRepository.findByTenantAndAgentAndId(principal.tenantId(), request.agentId(), request.runId())
                    .filter(run -> run.status() == RunStatus.WAITING_APPROVAL)
                    .ifPresent(run -> {
                        AgentRunResult expired = new AgentRunResult(
                                run.id(), RunStatus.DENIED, "", List.of(), run.startedAt(), now, "工具审批已过期");
                        runPersistenceService.complete(principal, run, expired, List.of());
                        deleteCheckpoint(principal, run);
                    });
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_APPROVAL_EXPIRE",
                    "TOOL_APPROVAL", request.id().toString(), "EXPIRED", "高风险工具审批请求已过期");
            return true;
        });
    }

    /** Run 进入终态后删除同一可信主体和 runId 对应的 AgentScope 状态槽。 */
    private void deleteCheckpoint(PrincipalRef principal, RunRecord run) {
        checkpointRepository.deleteSession(
                principal.tenantId(), principal.tenantId() + ":" + run.principalId(), run.id().toString());
    }

    private ToolApprovalView view(PrincipalRef principal, ToolApprovalRequest request) {
        boolean canDecide = request.status() == ToolApprovalStatus.PENDING
                && !request.isExpired(clock.instant())
                && request.requestedBy().equals(principal.principalId())
                && permissionEvaluator.check(principal, "agent:approve").allowed()
                && permissionEvaluator.check(principal, "agent:run").allowed();
        return new ToolApprovalView(
                request.id(), request.agentId(), request.conversationId(), request.runId(), request.status(),
                request.version(), redactor.redact(request.requestedByDisplayName()),
                request.decidedByDisplayName() == null ? null : redactor.redact(request.decidedByDisplayName()),
                request.decidedAt(), canDecide, request.expiresAt(), request.items().stream()
                .map(item -> new ToolApprovalItemView(
                        item.id(), item.toolCallId(), item.toolId(), item.toolName(), item.riskLevel(),
                        item.inputSummary(), item.decision())).toList());
    }

    /** 在 JDBC 模式复用同一短事务；memory 模式直接执行。 */
    private <T> T inTransaction(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        T result = transactionTemplate.execute(status -> action.get());
        return java.util.Objects.requireNonNull(result, "审批事务结果不能为空");
    }

    /**
     * 客户端仅能提交审批明细标识与决定。
     *
     * @param itemId 服务端公开的审批明细标识
     * @param decision 对本次具体工具调用的允许或拒绝决定
     */
    public record ItemDecision(UUID itemId, ToolApprovalDecision decision) {
    }

    /**
     * 审批请求对外视图，不包含 tenant、输入哈希或检查点。
     *
     * @param approvalId 审批请求公开标识
     * @param agentId 当前 Agent 标识
     * @param conversationId 当前会话标识
     * @param runId 被暂停的 Run 标识
     * @param status 审批请求生命周期状态
     * @param version 客户端提交决定时使用的乐观锁版本
     * @param requestedByDisplayName 已脱敏的运行发起人展示名称
     * @param decidedByDisplayName 已脱敏的审批人展示名称，待审批时为空
     * @param decidedAt 审批决定生效时间，待审批时为空
     * @param canDecide 当前认证主体是否可以提交决定，仅用于前端展示
     * @param expiresAt 服务端权威过期时间
     * @param items 本轮全部待确认工具调用的安全视图
     */
    public record ToolApprovalView(
            UUID approvalId, UUID agentId, UUID conversationId, UUID runId, ToolApprovalStatus status,
            long version, String requestedByDisplayName, String decidedByDisplayName, Instant decidedAt,
            boolean canDecide, Instant expiresAt, List<ToolApprovalItemView> items
    ) {
        public ToolApprovalView {
            items = List.copyOf(items);
        }
    }

    /**
     * 审批明细对外视图，只包含脱敏摘要。
     *
     * @param itemId 审批明细公开标识
     * @param toolCallId AgentScope 工具调用标识
     * @param toolId CM Agent 工具标识
     * @param toolName 创建审批时的工具名称快照
     * @param riskLevel 创建审批时的风险等级快照
     * @param inputSummary 已脱敏且限长的纯文本参数摘要
     * @param decision 已提交的决定，待审批时为空
     */
    public record ToolApprovalItemView(
            UUID itemId, String toolCallId, UUID toolId, String toolName,
            com.cmagent.core.domain.ToolRiskLevel riskLevel, String inputSummary, ToolApprovalDecision decision) {
    }

    /**
     * 决定接受后的 SSE 恢复结果。
     *
     * @param decision 已原子接受的审批权威状态
     * @param run 恢复后的运行结果，可能再次进入等待审批
     * @param assistantMessage 最终 assistant 消息；暂停或无最终消息时为空
     * @param nextApproval 再次遇到 HIGH 工具时创建的新审批；否则为空
     */
    public record ApprovalResumeOutcome(
            ToolApprovalView decision, AgentRunResult run,
            ConversationMessage assistantMessage, ToolApprovalView nextApproval) {
    }
}
