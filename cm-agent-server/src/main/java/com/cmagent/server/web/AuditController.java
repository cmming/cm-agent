package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditPageRequest;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit-events")
/** 审计查询接口，只允许读取当前租户的审计事件。 */
public class AuditController {

    private final AuditEventRepository auditEventRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;

    @Autowired
    /**
     * 创建 {@code AuditController} 实例并保存其运行所需依赖。
     *
     * @param auditEventRepository 负责访问相关领域数据的仓储。
     * @param permissionEvaluator 执行主体权限判断的组件。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param redactor 负责清理敏感文本的脱敏器。
     */
    public AuditController(
            AuditEventRepository auditEventRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor
    ) {
        this.auditEventRepository = auditEventRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.redactor = redactor;
    }
    /**
     * 创建 {@code AuditController} 实例并保存其运行所需依赖。
     *
     * @param auditEventRepository 负责访问相关领域数据的仓储。
     * @param permissionEvaluator 执行主体权限判断的组件。
     * @param auditAppender 负责追加安全审计事件的组件。
     */
    public AuditController(
            AuditEventRepository auditEventRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender
    ) {
        this(auditEventRepository, permissionEvaluator, auditAppender, new SensitiveDataRedactor());
    }

    /**
     * 按游标查询当前租户的审计事件。
     *
     * @param limit          单页最大事件数
     * @param cursor         上一页返回的游标，可为空
     * @param authentication 当前请求认证信息
     * @return 审计事件分页结果
     * @throws ResponseStatusException 未认证、无权限或游标格式错误时抛出
     */
    @GetMapping
    public AuditPage list(
            Authentication authentication,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "cursor", required = false) String cursor
    ) {
        if (limit < 1 || limit > 100) {
            throw invalidRequest();
        }
        PrincipalRef principal = principal(authentication);
        authorize(principal, "audit:read", "AUDIT", "audit-events");
        CursorPosition position = decodeCursor(cursor);
        if (position != null && !auditEventRepository.supportsCursorPagination()) {
            throw invalidRequest();
        }
        AuditPageRequest pageRequest = new AuditPageRequest(
                limit,
                position == null ? null : position.createdAt(),
                position == null ? null : position.id()
        );
        List<AuditEvent> storedItems = auditEventRepository.listByTenant(principal.tenantId(), pageRequest);
        String nextCursor = null;
        if (auditEventRepository.supportsCursorPagination() && storedItems.size() == limit && !storedItems.isEmpty()) {
            AuditEvent last = storedItems.getLast();
            boolean hasNext = !auditEventRepository.listByTenant(
                    principal.tenantId(), new AuditPageRequest(1, last.createdAt(), last.id())).isEmpty();
            if (hasNext) {
                nextCursor = encodeCursor(last);
            }
        }
        return new AuditPage(storedItems.stream().map(this::redact).toList(), nextCursor);
    }

    /**
     * 脱敏审计事件中的主体、资源和消息文本。
     *
     * @param event 待处理的审计事件。
     */
    private AuditEvent redact(AuditEvent event) {
        return new AuditEvent(
                event.id(), event.tenantId(), redactor.redact(event.principalId()), redactor.redact(event.eventType()),
                redactor.redact(event.resourceType()), redactor.redact(event.resourceId()), redactor.redact(event.status()),
                redactor.redact(event.message()), event.createdAt()
        );
    }

    /**
     * 解码并校验复合分页游标。
     *
     * @param cursor 分页游标，用于定位下一页数据。
     */
    private CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.isBlank()) {
                throw new IllegalArgumentException("空游标");
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException("游标格式不合法");
            }
            return new CursorPosition(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (RuntimeException ignored) {
            throw invalidRequest();
        }
    }

    /**
     * 将最后一条记录的位置编码为下一页游标。
     *
     * @param event 待处理的审计事件。
     */
    private String encodeCursor(AuditEvent event) {
        String value = event.createdAt() + "|" + event.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 创建表示审计分页参数不合法的 HTTP 400 异常。
     */
    private ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    /**
     * 从 Spring Security 认证对象提取可信主体上下文。
     *
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    /**
     * 校验主体是否拥有目标权限，并在拒绝时记录审计事件。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param permission 待校验的权限编码。
     * @param resourceType 审计资源类型。
     * @param resourceId 目标 resource 标识，用于定位本次处理对象。
     */
    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * 创建 {@code AuditPage} 实例并保存其运行所需依赖。
     */
    public record AuditPage(List<AuditEvent> items, String nextCursor) {
        /**
         * 校验并构造 {@code AuditPage} 实例。
         *
         * @param items 当前页数据。
         * @param nextCursor 下一页复合游标，可为空。
         */
        public AuditPage {
            items = List.copyOf(items);
        }
    }

    /**
     * 封装 {@code CursorPosition} 在当前流程中使用的不可变数据。
     */
    private record CursorPosition(Instant createdAt, UUID id) {
    }
}
