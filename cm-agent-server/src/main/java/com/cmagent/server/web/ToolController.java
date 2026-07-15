package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ManagementCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
    private final ToolDefinitionRepository toolRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final ManagementCommandService managementCommandService;

    public ToolController(
            ToolDefinitionRepository toolRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            ManagementCommandService managementCommandService
    ) {
        this.toolRepository = toolRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.managementCommandService = managementCommandService;
    }

    @GetMapping
    public List<ToolDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "TOOL", "list");
        return toolRepository.listByTenant(principal.tenantId());
    }

    @PostMapping
    public ToolDefinition create(@Valid @RequestBody ToolCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", "create");
        return managementCommandService.createTool(
                principal, request.name(), request.description(), request.type(), request.riskLevel()
        );
    }

    @PostMapping("/{id}/grants")
    public ToolGrant grant(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ToolGrantRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        return managementCommandService.grantTool(principal, id, request.agentId());
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
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

    public record ToolCreateRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull ToolType type,
            @NotNull ToolRiskLevel riskLevel
    ) {
    }

    public record ToolGrantRequest(@NotNull UUID agentId) {
    }
}
