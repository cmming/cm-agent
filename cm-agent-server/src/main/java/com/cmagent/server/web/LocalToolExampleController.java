package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.LocalToolExampleSummary;
import com.cmagent.server.service.MysqlLocalExampleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 提供固定内置 LOCAL 示例的只读目录和显式安装入口。 */
@RestController
@RequestMapping("/api/tools/local-examples")
@Profile("mysql & !prod & !production & !supabase")
@ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "jdbc")
public class LocalToolExampleController {
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final MysqlLocalExampleService service;

    public LocalToolExampleController(
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            MysqlLocalExampleService service
    ) {
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.service = Objects.requireNonNull(service, "service 不能为空");
    }

    @GetMapping
    public List<LocalToolExampleSummary> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "local-examples");
        return service.list(principal);
    }

    @PostMapping("/{key}")
    public LocalToolExampleSummary install(@PathVariable("key") String key, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", key);
        return service.install(principal, key);
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, "TOOL", resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }
}
