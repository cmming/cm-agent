package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents/{agentId}/runs")
public class RunController {

    private final AgentRuntime runtime;
    private final InMemoryPlatformStore store;
    private final PermissionEvaluator permissionEvaluator;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;

    public RunController(AgentRuntime runtime,
                         InMemoryPlatformStore store,
                         PermissionEvaluator permissionEvaluator,
                         ToolAuthorizationPolicy toolAuthorizationPolicy) {
        this.runtime = runtime;
        this.store = store;
        this.permissionEvaluator = permissionEvaluator;
        this.toolAuthorizationPolicy = toolAuthorizationPolicy;
    }

    @PostMapping
    public AgentRunResult run(@PathVariable("agentId") UUID agentId, @Valid @RequestBody RunRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run");

        AgentDefinition agent = store.findAgent(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (!agent.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 已禁用");
        }

        List<ToolDefinition> authorizedTools = authorizedTools(principal, agent);

        try {
            AgentRunResult result = runtime.run(new AgentRunRequest(
                    principal.tenantId(),
                    agent.id(),
                    principal,
                    request.input(),
                    authorizedTools
            ));
            appendAuditEvent(principal, agent, result.status().name(), result.status() == RunStatus.SUCCEEDED ? "Agent 运行完成" : defaultMessage(result));
            return result;
        } catch (RuntimeException ex) {
            appendAuditEvent(principal, agent, RunStatus.FAILED.name(), defaultMessage(ex));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 运行失败", ex);
        }
    }

    private List<ToolDefinition> authorizedTools(PrincipalRef principal, AgentDefinition agent) {
        List<ToolGrant> grants = store.listGrants(principal.tenantId(), agent.id());
        Map<UUID, ToolDefinition> tools = new LinkedHashMap<>();
        for (ToolGrant grant : grants) {
            if (!grant.granted()) {
                continue;
            }
            store.findTool(principal.tenantId(), grant.toolId())
                    .ifPresent(tool -> {
                        AuthorizationDecision decision = toolAuthorizationPolicy.check(principal, agent.id(), tool, grants);
                        if (decision.allowed()) {
                            tools.putIfAbsent(tool.id(), tool);
                        }
                    });
        }
        return new ArrayList<>(tools.values());
    }

    private String defaultMessage(AgentRunResult result) {
        return result.errorMessage() == null || result.errorMessage().isBlank()
                ? "Agent 运行失败"
                : result.errorMessage();
    }

    private String defaultMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Agent 运行失败"
                : ex.getMessage();
    }

    private void appendAuditEvent(PrincipalRef principal, AgentDefinition agent, String status, String message) {
        store.append(new AuditEvent(
                UUID.randomUUID(),
                principal.tenantId(),
                principal.principalId(),
                "AGENT_RUN",
                "AGENT",
                agent.id().toString(),
                status,
                message,
                Instant.now()
        ));
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

    public record RunRequest(@NotBlank String input) {
    }
}
