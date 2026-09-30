package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillReleaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * 技能正式发布和历史回滚接口；只接受认证主体提供的租户边界。
 *
 * <p>路径参数显式声明协议名称：Spring MVC 在没有编译器 {@code -parameters} 元数据时
 * 不能从 Java 参数推导变量名，省略名称会在进入业务授权与发布门禁前拒绝合法请求。</p>
 */
@RestController
@RequestMapping("/api/skills/{skillId}")
public class SkillReleaseController {
    private final SkillReleaseService releases;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;

    /** 创建发布控制器。 */
    public SkillReleaseController(SkillReleaseService releases, PermissionEvaluator permissions, AuditAppender audit) {
        this.releases = releases;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 正式发布当前候选版本。 */
    @PostMapping("/releases")
    public ResponseEntity<SkillRelease> publish(@PathVariable("skillId") UUID skillId,
                                                @Valid @RequestBody SkillRequests.PublishRequest request,
                                                Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, skillId);
        SkillRelease release = releases.publish(principal, skillId, request.candidateVersionId(),
                request.expectedPublishedVersionId(), request.qualifyingTrialRunId());
        return ResponseEntity.status(HttpStatus.CREATED).body(release);
    }

    /** 将曾正式发布的版本重新发布为当前版本。 */
    @PostMapping("/rollbacks")
    public ResponseEntity<SkillRelease> rollback(@PathVariable("skillId") UUID skillId,
                                                 @Valid @RequestBody SkillRequests.RollbackRequest request,
                                                 Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, skillId);
        SkillRelease release = releases.rollback(principal, skillId, request.targetVersionId(),
                request.expectedPublishedVersionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(release);
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(),
                Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, UUID skillId) {
        AuthorizationDecision decision = permissions.check(principal, "skill:write");
        if (!decision.allowed()) {
            audit.accessDenied(principal, "SKILL", skillId.toString(), "skill:write", decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }
}
