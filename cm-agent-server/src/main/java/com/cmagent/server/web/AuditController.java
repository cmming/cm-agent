package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {

    private final InMemoryPlatformStore store;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public AuditController(InMemoryPlatformStore store, PermissionEvaluator permissionEvaluator, AuditAppender auditAppender) {
        this.store = store;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    @GetMapping
    public List<AuditEvent> list(Authentication authentication, @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在 1 到 500 之间");
        }
        PrincipalRef principal = principal(authentication);
        authorize(principal, "audit:read", "AUDIT", "audit-events");
        return store.listAuditEvents(principal.tenantId(), limit);
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
}
