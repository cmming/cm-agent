package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillPreflightRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillDependencyMappingService;
import com.cmagent.server.service.SkillPreflightResult;
import com.cmagent.server.service.SkillPreflightService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 技能依赖映射与两层预检接口。
 *
 * <p>权限与租户只来自当前 JWT 主体；Controller 只做校验、权限入口和响应组装，映射事务与
 * 预检编排位于 Service。读取接口不会返回工具端点、HTTP 请求头或授权内部字段。</p>
 */
@RestController
@RequestMapping("/api/skills/{id}")
public class SkillDependencyController {
    private final SkillDefinitionRepository definitions;
    private final SkillDependencyRepository dependencies;
    private final SkillDependencyMappingRepository mappings;
    private final SkillPreflightRepository preflights;
    private final SkillDependencyMappingService mappingService;
    private final SkillPreflightService preflightService;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;

    /** 创建依赖与预检控制器。 */
    public SkillDependencyController(
            SkillDefinitionRepository definitions,
            SkillDependencyRepository dependencies,
            SkillDependencyMappingRepository mappings,
            SkillPreflightRepository preflights,
            SkillDependencyMappingService mappingService,
            SkillPreflightService preflightService,
            PermissionEvaluator permissions,
            AuditAppender audit) {
        this.definitions = definitions;
        this.dependencies = dependencies;
        this.mappings = mappings;
        this.preflights = preflights;
        this.mappingService = mappingService;
        this.preflightService = preflightService;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 返回技能当前候选或发布版本声明的依赖映射状态。 */
    @GetMapping("/dependencies")
    public SkillResponses.DependencyView dependencies(
            @PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", id.toString());
        return dependencyView(principal, id);
    }

    /** 条件更新一个逻辑依赖映射。 */
    @PutMapping("/dependencies/{logicalKey}")
    public SkillResponses.DependencyView updateMapping(
            @PathVariable("id") UUID id,
            @PathVariable("logicalKey") String logicalKey,
            @Valid @RequestBody SkillRequests.DependencyMappingRequest request,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", id.toString());
        // 请求体中的 tenantId 从未参与判断；映射目标由服务端在当前租户内重新解析。
        mappingService.map(principal, id, logicalKey, request.toolId(), request.expectedRevision());
        return dependencyView(principal, id);
    }

    /** 对指定版本执行结构或 Agent 预检。 */
    @PostMapping("/preflights")
    public SkillResponses.PreflightView preflight(
            @PathVariable("id") UUID id,
            @RequestParam("versionId") UUID versionId,
            @RequestParam("scope") SkillPreflightScope scope,
            @RequestParam(name = "agentId", required = false) UUID agentId,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", id.toString());
        return view(preflightService.preflight(principal, id, versionId, scope, agentId));
    }

    /** 查询一次历史预检的汇总与明细。 */
    @GetMapping("/preflights/{checkId}")
    public SkillResponses.PreflightView preflight(
            @PathVariable("id") UUID id,
            @PathVariable("checkId") UUID checkId,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", id.toString());
        requireDefinition(principal.tenantId(), id);
        var check = preflights.find(principal.tenantId(), checkId)
                .filter(item -> item.skillId().equals(id))
                .orElseThrow(this::notFound);
        return new SkillResponses.PreflightView(
                summary(check),
                preflights.listItems(principal.tenantId(), checkId).stream()
                        .map(SkillDependencyController::item).toList());
    }

    private SkillResponses.DependencyView dependencyView(PrincipalRef principal, UUID skillId) {
        SkillDefinition definition = requireDefinition(principal.tenantId(), skillId);
        UUID versionId = definition.currentVersionId();
        List<SkillDependency> declared = versionId == null ? List.of()
                : dependencies.list(principal.tenantId(), skillId, versionId);
        List<SkillDependencyMapping> current = mappings.list(principal.tenantId(), skillId);
        List<SkillResponses.DependencyEntry> items = declared.stream().map(dependency -> {
            Optional<SkillDependencyMapping> mapping = current.stream()
                    .filter(item -> item.logicalKey().equals(dependency.logicalKey())).findFirst();
            return new SkillResponses.DependencyEntry(
                    dependency.logicalKey(), dependency.required(), dependency.description(),
                    mapping.map(SkillDependencyMapping::toolId).orElse(null), null, null, "");
        }).toList();
        return new SkillResponses.DependencyView(skillId, definition.dependencyMappingRevision(), items);
    }

    private SkillResponses.PreflightView view(SkillPreflightResult result) {
        return new SkillResponses.PreflightView(summary(result.check()),
                result.items().stream().map(SkillDependencyController::item).toList());
    }

    private static SkillResponses.PreflightSummary summary(com.cmagent.core.domain.SkillPreflightCheck check) {
        return new SkillResponses.PreflightSummary(check.id(), check.skillId(), check.versionId(),
                check.mappingRevision(), check.scope(), check.agentId(), check.status(), check.createdAt());
    }

    private static SkillResponses.PreflightItemView item(SkillPreflightItem item) {
        return new SkillResponses.PreflightItemView(item.checkId(), item.agentId(), item.logicalKey(),
                item.required(), item.toolId(), item.status(), item.errorCode(), item.message(), item.errorId());
    }

    private SkillDefinition requireDefinition(UUID tenantId, UUID skillId) {
        return definitions.find(tenantId, skillId).orElseThrow(this::notFound);
    }

    private SkillAccessException notFound() {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND,
                "技能或关联资源不存在", UUID.randomUUID().toString(), false);
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
            audit.accessDenied(principal, "SKILL", resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }
}
