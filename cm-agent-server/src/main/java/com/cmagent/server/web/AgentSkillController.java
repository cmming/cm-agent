package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillManagementService;
import com.cmagent.server.service.SkillQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Agent 技能绑定接口；绑定不授予任何业务工具权限。 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillController {
    private final SkillManagementService management;
    private final SkillQueryService queries;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;

    /** 创建 Agent 技能绑定控制器。 */
    public AgentSkillController(SkillManagementService management, SkillQueryService queries,
                                PermissionEvaluator permissions, AuditAppender audit) {
        this.management = management;
        this.queries = queries;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 返回 Agent 已绑定技能摘要，不暴露技能正文。 */
    @GetMapping
    public List<SkillResponses.Binding> list(@PathVariable("agentId") UUID agentId, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", agentId.toString());
        return queries.bindings(principal, agentId);
    }

    /** 幂等绑定技能，同时要求 Agent 写权限与技能读权限。 */
    @PutMapping("/{skillId}")
    public SkillResponses.Binding bind(@PathVariable("agentId") UUID agentId,
                                       @PathVariable("skillId") UUID skillId,
                                       Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:write", agentId.toString());
        authorize(principal, "skill:read", skillId.toString());
        AgentSkillBinding binding = management.bind(principal, agentId, skillId);
        return queries.bindings(principal, agentId).stream()
                .filter(item -> item.bindingId().equals(binding.id())).findFirst().orElseThrow();
    }

    /** 幂等解绑技能；功能关闭时也允许撤销。 */
    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> unbind(@PathVariable("agentId") UUID agentId,
                                       @PathVariable("skillId") UUID skillId,
                                       Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:write", agentId.toString());
        management.unbind(principal, agentId, skillId);
        return ResponseEntity.noContent().build();
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(),
                Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceId) {
        AuthorizationDecision decision = permissions.check(principal, permission);
        if (!decision.allowed()) {
            audit.accessDenied(principal, "AGENT_SKILL", resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }
}
