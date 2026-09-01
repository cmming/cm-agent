package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentTextDelta;
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
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final ConversationService conversationService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final TaskExecutor streamExecutor;
    private final ErrorDiagnosticLogger diagnosticLogger;

    public ConversationController(
            ConversationService conversationService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            @Qualifier("applicationTaskExecutor") TaskExecutor streamExecutor,
            ErrorDiagnosticLogger diagnosticLogger
    ) {
        this.conversationService = conversationService;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.streamExecutor = streamExecutor;
        this.diagnosticLogger = diagnosticLogger;
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
                    delta -> sendDelta(emitter, replyId, delta));
            send(emitter, "completed", result);
        } catch (RuntimeException failure) {
            send(emitter, "error", streamError(principal, agentId, conversationId, errorId, failure));
        } finally {
            emitter.complete();
        }
    }

    /**
     * SSE 响应提交后不能再由统一异常处理器修改状态码，因此在异步边界复用稳定错误码与脱敏文案。
     */
    private ConversationStreamError streamError(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String errorId,
            RuntimeException failure
    ) {
        ApiErrorCode code;
        String message;
        if (failure instanceof ResponseStatusException statusFailure) {
            int status = statusFailure.getStatusCode().value();
            code = status == HttpStatus.NOT_FOUND.value()
                    ? ApiErrorCode.CONVERSATION_NOT_FOUND
                    : ApiErrorCode.VALIDATION_FAILED;
            message = status == HttpStatus.BAD_REQUEST.value()
                    && statusFailure.getReason() != null && !statusFailure.getReason().isBlank()
                    ? statusFailure.getReason()
                    : status == HttpStatus.NOT_FOUND.value() ? "会话或 Agent 不存在" : "请求参数不合法";
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
        diagnosticLogger.error(new ErrorDiagnosticLogger.DiagnosticContext(
                errorId, "CONVERSATION_STREAM", code.name(), principal.tenantId().toString(),
                principal.principalId(), agentId.toString(), "-", "-", "-", "CONVERSATION"), failure);
        return new ConversationStreamError(code, message, errorId, conversationId);
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

    public record SendMessageRequest(@NotBlank String input) {
    }

    public record ConversationPage(List<Conversation> items, String nextCursor) {
        public ConversationPage {
            items = List.copyOf(items);
        }
    }

    public record MessagePage(List<ConversationMessage> items, Long nextSequence) {
        public MessagePage {
            items = List.copyOf(items);
        }
    }

    public record MessageStarted(String replyId) {
    }

    public record ConversationStreamError(
            ApiErrorCode code,
            String message,
            String errorId,
            UUID conversationId
    ) {
    }

    private record ConversationCursor(Instant updatedAt, UUID id) {
    }
}
