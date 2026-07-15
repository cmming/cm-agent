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
public class AuditController {

    private final AuditEventRepository auditEventRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;

    @Autowired
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

    public AuditController(
            AuditEventRepository auditEventRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender
    ) {
        this(auditEventRepository, permissionEvaluator, auditAppender, new SensitiveDataRedactor());
    }

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

    private AuditEvent redact(AuditEvent event) {
        return new AuditEvent(
                event.id(), event.tenantId(), redactor.redact(event.principalId()), redactor.redact(event.eventType()),
                redactor.redact(event.resourceType()), redactor.redact(event.resourceId()), redactor.redact(event.status()),
                redactor.redact(event.message()), event.createdAt()
        );
    }

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

    private String encodeCursor(AuditEvent event) {
        String value = event.createdAt() + "|" + event.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    public record AuditPage(List<AuditEvent> items, String nextCursor) {
        public AuditPage {
            items = List.copyOf(items);
        }
    }

    private record CursorPosition(Instant createdAt, UUID id) {
    }
}
