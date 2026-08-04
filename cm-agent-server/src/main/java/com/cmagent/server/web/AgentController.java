package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ManagementCommandService;
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
/** Agent 管理接口；所有数据访问都限定在当前认证主体的租户范围内。 */
public class AgentController {
    private final AgentDefinitionRepository agentRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final ManagementCommandService managementCommandService;
    /**
     * 创建 {@code AgentController} 实例并保存其运行所需依赖。
     *
     * @param agentRepository 负责访问相关领域数据的仓储。
     * @param permissionEvaluator 执行主体权限判断的组件。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param managementCommandService 负责当前业务流程的服务。
     */
    public AgentController(
            AgentDefinitionRepository agentRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            ManagementCommandService managementCommandService
    ) {
        this.agentRepository = agentRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.managementCommandService = managementCommandService;
    }

    @GetMapping
    /**
     * 查询当前租户可见的 Agent 列表。
     *
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    public List<AgentDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", "list");
        return agentRepository.listByTenant(principal.tenantId());
    }

    @GetMapping("/{id}")
    /**
     * 查询单个 Agent，并在返回前执行资源级权限校验。
     *
     * @param id 目标资源标识。
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    public AgentDefinition get(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", id.toString());
        return agentRepository.findByTenantAndId(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    @PostMapping
    /**
     * 创建 Agent；业务编排和审计由命令服务统一处理。
     *
     * @param request 创建或更新 Agent 所需的请求 DTO
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    public AgentDefinition create(@Valid @RequestBody AgentCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:write", "AGENT", "create");
        return managementCommandService.createAgent(
                principal, request.name(), request.systemPrompt(), request.modelName()
        );
    }

    /**
     * 从 Spring Security 认证对象提取可信主体上下文。
     *
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    private PrincipalRef principal(Authentication authentication) {
        // 只接受 JWT 过滤器创建的会话主体，避免信任客户端提交的租户或权限信息。
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
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
     * 封装 {@code AgentCreateRequest} 在当前流程中使用的不可变数据。
     */
    public record AgentCreateRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String systemPrompt,
            @jakarta.validation.constraints.NotBlank String modelName
    ) {
    }
}
