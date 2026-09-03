package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.ConversationRunResult;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.runtime.ConversationService;
import com.cmagent.server.runtime.RunExecutionService;
import com.cmagent.server.runtime.ToolApprovalService;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话和消息 API，仅负责可信主体解析、授权、HTTP/SSE 合同与错误边界。
 *
 * <p>tenant、主体、角色、序号和 Run 关联均不接受客户端覆盖；它们由认证会话和服务端编排生成。
 * SSE 已提交响应后无法改写 HTTP 状态，因此异步失败必须转换为带稳定 {@code errorCode} 和
 * {@code errorId} 的事件。</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/conversations")
public class ConversationController {
    /** 受控业务拒绝使用 WARN；未预期故障仍由统一诊断器保留脱敏堆栈。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConversationController.class);
    private final ConversationService conversationService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final TaskExecutor streamExecutor;
    private final ErrorDiagnosticLogger diagnosticLogger;
    private final ToolApprovalService approvalService;
    /** SSE 已提交后同样必须脱敏业务层原因，不能直接复制异常消息。 */
    private final com.cmagent.server.security.SensitiveDataRedactor redactor;

    public ConversationController(
            ConversationService conversationService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            @Qualifier("applicationTaskExecutor") TaskExecutor streamExecutor,
            ErrorDiagnosticLogger diagnosticLogger,
            ToolApprovalService approvalService,
            com.cmagent.server.security.SensitiveDataRedactor redactor
    ) {
        this.conversationService = conversationService;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.streamExecutor = streamExecutor;
        this.diagnosticLogger = diagnosticLogger;
        this.approvalService = approvalService;
        this.redactor = redactor;
    }

    @PostMapping
    /**
     * 创建指定 Agent 的空会话，需要 {@code agent:run} 权限。
     */
    public Conversation create(@PathVariable("agentId") UUID agentId, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "AGENT", agentId.toString());
        return conversationService.create(principal, agentId);
    }

    @GetMapping
    /**
     * 使用服务端编码的 {@code updatedAt + id} 游标分页读取会话，需要 {@code agent:read} 权限。
     */
    public ConversationPage list(
            @PathVariable("agentId") UUID agentId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", agentId.toString());
        ConversationCursor position = decodeConversationCursor(cursor);
        List<Conversation> items = conversationService.list(principal, agentId, new ConversationPageRequest(
                limit,
                position == null ? null : position.updatedAt(),
                position == null ? null : position.id()));
        String nextCursor = items.size() == limit && !items.isEmpty()
                ? encodeConversationCursor(items.getLast())
                : null;
        return new ConversationPage(items, nextCursor);
    }

    @GetMapping("/{conversationId}")
    /**
     * 读取单个会话元数据，需要 {@code agent:read} 权限。
     */
    public Conversation get(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "CONVERSATION", conversationId.toString());
        return conversationService.get(principal, agentId, conversationId);
    }

    @GetMapping("/{conversationId}/messages")
    /**
     * 从排他序号游标之后正序读取消息，需要 {@code agent:read} 权限。
     */
    public MessagePage messages(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "afterSequence", defaultValue = "0") long afterSequence,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "CONVERSATION", conversationId.toString());
        List<ConversationMessage> items = conversationService.messages(
                principal, agentId, conversationId, new MessagePageRequest(limit, afterSequence));
        Long nextSequence = items.size() == limit && !items.isEmpty() ? items.getLast().sequence() : null;
        return new MessagePage(items, nextSequence);
    }

    @PostMapping("/{conversationId}/messages")
    /**
     * 同步追加 USER 消息并执行本轮 Runtime，需要 {@code agent:run} 权限。
     */
    public ConversationRunResult send(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "CONVERSATION", conversationId.toString());
        return conversationService.send(principal, agentId, conversationId, request.input());
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    /**
     * 异步执行会话续聊并以 SSE 返回生命周期事件，需要 {@code agent:run} 权限。
     *
     * <p>事件顺序为 {@code started}、可选 {@code message-started}/{@code delta}、最终
     * {@code completed} 或 {@code error}；创建 emitter 后由任务执行器完成实际运行，避免阻塞 MVC 请求线程。</p>
     */
    public SseEmitter stream(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "CONVERSATION", conversationId.toString());
        String errorId = RequestCorrelationFilter.errorIdOf(servletRequest);
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> executeStream(
                emitter, principal, agentId, conversationId, request.input(), errorId));
        return emitter;
    }

    /**
     * 执行已授权的异步会话，并在异常边界转换为安全的 SSE 错误事件。
     */
    private void executeStream(
            SseEmitter emitter,
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            String errorId
    ) {
        AtomicReference<String> replyId = new AtomicReference<>();
        try {
            ConversationRunResult result = conversationService.send(
                    principal,
                    agentId,
                    conversationId,
                    input,
                    started -> send(emitter, "started", started),
                    delta -> sendDelta(emitter, replyId, delta),
                    progress -> send(emitter, "progress", progress));
            if (result.run().status() == com.cmagent.core.domain.RunStatus.WAITING_APPROVAL) {
                ToolApprovalService.ToolApprovalView approval = approvalService.listPending(
                                principal, agentId, conversationId).stream()
                        .filter(item -> item.runId().equals(result.run().runId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("等待审批 Run 缺少审批请求"));
                send(emitter, "approval-required", approval);
            } else {
                send(emitter, "completed", result);
            }
        } catch (RuntimeException failure) {
            send(emitter, "error", streamError(principal, agentId, conversationId, errorId, failure, false));
        } finally {
            emitter.complete();
        }
    }

    @GetMapping("/{conversationId}/approvals")
    /** 查询当前会话待审批请求，需要读取权限；canDecide 由服务端另行计算。 */
    public ApprovalPage approvals(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "status", defaultValue = "PENDING") String status,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "CONVERSATION", conversationId.toString());
        if (!"PENDING".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第一版仅支持查询 PENDING 审批");
        }
        conversationService.get(principal, agentId, conversationId);
        return new ApprovalPage(approvalService.listPending(principal, agentId, conversationId));
    }

    /**
     * 分页读取当前会话的已处理审批，终态视图只读，不要求审批权限。
     *
     * <p>先从认证主体校验 tenant 与会话归属；游标仅用于定位，不能赋予资源访问权限。
     * 新决定可能出现在首屏，加载旧页不会自动重放或接受任何工具调用。</p>
     *
     * @param agentId Agent 路由标识
     * @param conversationId 会话路由标识
     * @param limit 每页数量，范围 1 到 100，默认 20
     * @param cursor 上一页返回的不透明游标，首页省略
     * @param authentication 当前认证上下文
     * @param servletRequest 用于把可信诊断上下文传递到统一错误边界
     * @return 已处理审批与可空的下一页游标
     */
    @GetMapping("/{conversationId}/approvals/history")
    public ApprovalHistoryPage approvalHistory(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PrincipalRef principal = principal(authentication);
        servletRequest.setAttribute(ErrorDiagnosticLogger.DiagnosticContext.class.getName(),
                new ErrorDiagnosticLogger.DiagnosticContext(RequestCorrelationFilter.errorIdOf(servletRequest),
                        "APPROVAL_HISTORY", ApiErrorCode.INTERNAL_ERROR.name(), principal.tenantId().toString(),
                        principal.principalId(), agentId.toString(), "-", "-", "-", "CONVERSATION:" + conversationId));
        authorize(principal, "agent:read", "CONVERSATION", conversationId.toString());
        conversationService.get(principal, agentId, conversationId);
        ToolApprovalHistoryPageRequest page = decodeApprovalHistoryCursor(agentId, conversationId, limit, cursor);
        List<ToolApprovalService.ToolApprovalView> items = approvalService.listHistory(principal, agentId, conversationId, page);
        String nextCursor = null;
        if (items.size() == limit) {
            var last = items.getLast();
            // 探测一条更旧记录，不返回看似可翻页但实际为空的游标。
            if (!approvalService.listHistory(principal, agentId, conversationId,
                    new ToolApprovalHistoryPageRequest(1, last.decidedAt(), last.approvalId())).isEmpty()) {
                String value = "1|" + agentId + "|" + conversationId + "|" + last.decidedAt() + "|" + last.approvalId();
                nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new ApprovalHistoryPage(items, nextCursor);
    }

    /** 拒绝超长、跨会话或损坏的游标，统一返回安全参数错误而不暴露解码异常。 */
    private ToolApprovalHistoryPageRequest decodeApprovalHistoryCursor(UUID agentId, UUID conversationId, int limit, String cursor) {
        try {
            if (cursor == null) return new ToolApprovalHistoryPageRequest(limit, null, null);
            if (cursor.isBlank() || cursor.length() > 256) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !"1".equals(fields[0]) || !agentId.toString().equals(fields[1])
                    || !conversationId.toString().equals(fields[2])) throw new IllegalArgumentException();
            Instant decidedAt = Instant.parse(fields[3]);
            if (decidedAt.isBefore(Instant.EPOCH) || decidedAt.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) {
                throw new IllegalArgumentException();
            }
            UUID id = UUID.fromString(fields[4]);
            if (!id.toString().equals(fields[4])) throw new IllegalArgumentException();
            return new ToolApprovalHistoryPageRequest(limit, decidedAt, id);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批历史分页参数不合法，请重新加载会话");
        }
    }

    @GetMapping("/{conversationId}/approvals/{approvalId}")
    /** 查询单个审批权威状态，跨租户、Agent、会话及不存在统一返回 404。 */
    public ToolApprovalService.ToolApprovalView approval(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("approvalId") UUID approvalId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "TOOL_APPROVAL", approvalId.toString());
        conversationService.get(principal, agentId, conversationId);
        return approvalService.get(principal, agentId, conversationId, approvalId);
    }

    @PostMapping(value = "/{conversationId}/approvals/{approvalId}/decision/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    /** 原子提交全部明细决定，并流式恢复原 Run。 */
    public SseEmitter decideApproval(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("approvalId") UUID approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:approve", "TOOL_APPROVAL", approvalId.toString());
        // 审批权限不能恢复已失去运行权限的主体；两项权限必须取自本次认证结果。
        authorize(principal, "agent:run", "TOOL_APPROVAL", approvalId.toString());
        List<ToolApprovalService.ItemDecision> decisions = request.decisions().stream()
                .map(item -> new ToolApprovalService.ItemDecision(item.itemId(), item.decision())).toList();
        // 状态、版本、资源边界和决定完整性在响应提交前校验，正常业务错误保持 HTTP 4xx。
        approvalService.validateForSubmission(
                principal, agentId, conversationId, approvalId, request.expectedVersion(), decisions);
        String errorId = RequestCorrelationFilter.errorIdOf(servletRequest);
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> executeApprovalStream(
                emitter, principal, agentId, conversationId, approvalId,
                request.expectedVersion(), decisions, errorId));
        return emitter;
    }

    private void executeApprovalStream(
            SseEmitter emitter,
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            List<ToolApprovalService.ItemDecision> decisions,
            String errorId
    ) {
        AtomicReference<String> replyId = new AtomicReference<>();
        try {
            ToolApprovalService.ApprovalResumeOutcome outcome = approvalService.decideAndResume(
                    principal, agentId, conversationId, approvalId, expectedVersion, decisions,
                    decision -> send(emitter, "approval-decision", new ApprovalDecisionAccepted(
                            decision.approvalId(), decision.status(), decision.version(), decision.decidedAt())),
                    delta -> sendDelta(emitter, replyId, delta),
                    progress -> send(emitter, "progress", progress));
            if (outcome.nextApproval() != null) {
                send(emitter, "approval-required", outcome.nextApproval());
            } else {
                send(emitter, "completed", outcome);
            }
        } catch (RuntimeException failure) {
            send(emitter, "error", streamError(principal, agentId, conversationId, errorId, failure, true));
        } finally {
            emitter.complete();
        }
    }

    /**
     * SSE 响应提交后不能再由统一异常处理器修改状态码，因此在异步边界复用稳定错误码与脱敏文案。
     *
     * @param principal 当前可信认证主体
     * @param agentId 当前 Agent 标识
     * @param conversationId 当前会话标识
     * @param errorId 与后台诊断日志一致的错误编号
     * @param failure 待分类且不得直接暴露给前端的异常
     * @param approvalFlow 是否处于审批恢复流，用于选择审批专用稳定错误码
     * @return 可安全发送给前端的 SSE 错误载荷
     */
    private ConversationStreamError streamError(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String errorId,
            RuntimeException failure,
            boolean approvalFlow
    ) {
        ApiErrorCode code;
        String message;
        if (failure instanceof ResponseStatusException statusFailure) {
            int status = statusFailure.getStatusCode().value();
            if (approvalFlow) {
                code = switch (HttpStatus.valueOf(status)) {
                    case NOT_FOUND -> ApiErrorCode.TOOL_APPROVAL_NOT_FOUND;
                    case CONFLICT -> ApiErrorCode.TOOL_APPROVAL_CONFLICT;
                    case GONE -> ApiErrorCode.TOOL_APPROVAL_EXPIRED;
                    case BAD_REQUEST -> ApiErrorCode.TOOL_APPROVAL_INVALID_DECISION;
                    case FORBIDDEN -> ApiErrorCode.FORBIDDEN;
                    default -> ApiErrorCode.VALIDATION_FAILED;
                };
                message = statusFailure.getReason() == null || statusFailure.getReason().isBlank()
                        ? "审批恢复失败"
                        : statusFailure.getReason();
            } else {
                code = status == HttpStatus.NOT_FOUND.value()
                        ? ApiErrorCode.CONVERSATION_NOT_FOUND
                        : status == HttpStatus.CONFLICT.value()
                            ? ApiErrorCode.TOOL_APPROVAL_CONFLICT
                            : ApiErrorCode.VALIDATION_FAILED;
                message = status == HttpStatus.NOT_FOUND.value()
                        ? "会话或 Agent 不存在"
                        : statusFailure.getReason() == null || statusFailure.getReason().isBlank()
                            ? "请求参数不合法"
                            : statusFailure.getReason();
            }
        } else if (failure instanceof AuditPersistenceException) {
            code = ApiErrorCode.AUDIT_UNAVAILABLE;
            message = "审计服务暂不可用";
        } else if (failure instanceof DataAccessException) {
            code = ApiErrorCode.PERSISTENCE_UNAVAILABLE;
            message = "数据服务暂不可用";
        } else if (failure instanceof RunExecutionService.RuntimeExecutionException) {
            code = ApiErrorCode.RUNTIME_ERROR;
            message = "会话运行失败";
        } else {
            code = ApiErrorCode.INTERNAL_ERROR;
            message = "服务内部错误";
        }
        String safeMessage = redactor.redact(message);
        if (failure instanceof ResponseStatusException statusFailure && statusFailure.getStatusCode().is4xxClientError()) {
            log.warn("会话请求被拒绝。errorId={}, errorCode={}, tenantId={}, principalId={}, agentId={}, conversationId={}, reason={}",
                    errorId, code.name(), principal.tenantId(), principal.principalId(), agentId, conversationId, safeMessage);
        } else {
            diagnosticLogger.error(new ErrorDiagnosticLogger.DiagnosticContext(
                    errorId, "CONVERSATION_STREAM", code.name(), principal.tenantId().toString(),
                    principal.principalId(), agentId.toString(), "-", "-", "-", "CONVERSATION:" + conversationId), failure);
        }
        return new ConversationStreamError(code, safeMessage, errorId, conversationId);
    }

    /**
     * 首次观察到 Runtime 的 {@code replyId} 时补发一次消息开始事件，不能在未收到 Runtime 标识前伪造它。
     */
    private void sendDelta(SseEmitter emitter, AtomicReference<String> currentReplyId, AgentTextDelta delta) {
        if (delta.replyId() != null && currentReplyId.compareAndSet(null, delta.replyId())) {
            send(emitter, "message-started", new MessageStarted(delta.replyId()));
        }
        send(emitter, "delta", delta);
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // 浏览器断开不能取消已经持久化 USER 消息并启动的模型运行。
        }
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(
                session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(
            PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * 解码不透明游标；格式错误统一按请求参数错误处理，不泄露解析细节。
     */
    private ConversationCursor decodeConversationCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 2) {
                throw new IllegalArgumentException("游标格式不合法");
            }
            return new ConversationCursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求参数不合法");
        }
    }

    private String encodeConversationCursor(Conversation conversation) {
        String value = conversation.updatedAt() + "|" + conversation.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 客户端允许提交的唯一消息写入字段。
     *
     * <p>tenant、角色、序号、消息标识和 Run 关联均由服务端生成，不能通过请求体覆盖可信会话边界。</p>
     */
    public record SendMessageRequest(@NotBlank String input) {
    }

    /** 使用不透明复合游标返回的会话列表页。 */
    public record ConversationPage(List<Conversation> items, String nextCursor) {
        public ConversationPage {
            items = List.copyOf(items);
        }
    }

    /** 按会话内稳定序号正序返回的消息列表页。 */
    public record MessagePage(List<ConversationMessage> items, Long nextSequence) {
        public MessagePage {
            items = List.copyOf(items);
        }
    }

    /** Runtime 首次实际提供 {@code replyId} 后发送的 SSE 消息开始事件。 */
    public record MessageStarted(String replyId) {
    }

    /** SSE 已提交后用于报告安全错误信息的终态事件载荷。 */
    public record ConversationStreamError(
            ApiErrorCode code,
            String message,
            String errorId,
            UUID conversationId
    ) {
    }

    /**
     * 固定 items 字段的待审批列表响应。
     *
     * @param items 当前会话未过期的待审批请求
     */
    public record ApprovalPage(List<ToolApprovalService.ToolApprovalView> items) {
        public ApprovalPage {
            items = List.copyOf(items);
        }
    }

    /**
     * 已处理审批的只读历史页，不携带原始工具参数或运行检查点。
     *
     * @param items 按决定时间及审批 ID 降序排列的安全视图
     * @param nextCursor 下一页不透明游标；没有更旧记录时为 {@code null}
     */
    public record ApprovalHistoryPage(List<ToolApprovalService.ToolApprovalView> items, String nextCursor) {
        public ApprovalHistoryPage { items = List.copyOf(items); }
    }

    /**
     * 审批流提交体，不允许携带 tenant、工具名称或参数。
     *
     * @param expectedVersion 审批请求的预期乐观锁版本
     * @param decisions 必须完整覆盖本轮全部审批明细的决定
     */
    public record ApprovalDecisionRequest(
            @PositiveOrZero long expectedVersion,
            @NotEmpty List<@Valid ApprovalItemDecisionRequest> decisions
    ) {
        public ApprovalDecisionRequest {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    /**
     * 单项决定只由服务端公开的 itemId 关联。
     *
     * @param itemId 审批明细公开标识
     * @param decision 对该工具调用的允许或拒绝决定
     */
    public record ApprovalItemDecisionRequest(
            @NotNull UUID itemId,
            @NotNull ToolApprovalDecision decision
    ) {
    }

    /**
     * 决定原子写入后发送的首个 SSE 事件。
     *
     * @param approvalId 审批请求标识
     * @param status 原子决定后的审批状态
     * @param version 原子递增后的版本
     * @param decidedAt 决定生效时间
     */
    public record ApprovalDecisionAccepted(
            UUID approvalId,
            com.cmagent.core.domain.ToolApprovalStatus status,
            long version,
            Instant decidedAt
    ) {
    }

    private record ConversationCursor(Instant updatedAt, UUID id) {
    }
}
