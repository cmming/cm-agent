package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
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

    private final InMemoryPlatformStore store;
    private final PermissionEvaluator permissionEvaluator;

    public ToolController(InMemoryPlatformStore store, PermissionEvaluator permissionEvaluator) {
        this.store = store;
        this.permissionEvaluator = permissionEvaluator;
    }

    @GetMapping
    public List<ToolDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read");
        return store.listTools(principal.tenantId());
    }

    @PostMapping
    public ToolDefinition create(@Valid @RequestBody ToolCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant");
        ToolDefinition tool = new ToolDefinition(
                UUID.randomUUID(),
                principal.tenantId(),
                request.name(),
                request.description(),
                request.type(),
                "{\"type\":\"object\"}",
                request.riskLevel(),
                true,
                "",
                principal.principalId(),
                principal.principalId()
        );
        return store.saveTool(tool);
    }

    @PostMapping("/{id}/grants")
    public ToolGrant grant(@PathVariable("id") UUID id, @Valid @RequestBody ToolGrantRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant");

        ToolDefinition tool = store.findTool(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = store.findAgent(principal.tenantId(), request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));

        ToolGrant grant = new ToolGrant(principal.tenantId(), tool.id(), agent.id(), "", true);
        ToolGrant savedGrant = store.saveGrant(grant);
        store.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
        return savedGrant;
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
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
