package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import jakarta.validation.Valid;
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
@RequestMapping("/api/agents")
public class AgentController {

    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final InMemoryPlatformStore store;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public AgentController(InMemoryPlatformStore store, PermissionEvaluator permissionEvaluator, AuditAppender auditAppender) {
        this.store = store;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    @GetMapping
    public List<AgentDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", "list");
        return store.listAgents(principal.tenantId());
    }

    @GetMapping("/{id}")
    public AgentDefinition get(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", id.toString());
        return store.findAgent(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    @PostMapping
    public AgentDefinition create(@Valid @RequestBody AgentCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:write", "AGENT", "create");
        AgentDefinition agent = new AgentDefinition(
                UUID.randomUUID(),
                principal.tenantId(),
                request.name(),
                "",
                request.systemPrompt(),
                MODEL_PROVIDER_ID,
                request.modelName(),
                0.2d,
                6,
                true,
                List.of(),
                principal.principalId(),
                principal.principalId()
        );
        AgentDefinition savedAgent = store.saveAgent(agent);
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "AGENT_CREATE",
                "AGENT",
                savedAgent.id().toString(),
                "SUCCEEDED",
                "Agent 创建成功"
        );
        return savedAgent;
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

    public record AgentCreateRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String systemPrompt,
            @jakarta.validation.constraints.NotBlank String modelName
    ) {
    }
}
