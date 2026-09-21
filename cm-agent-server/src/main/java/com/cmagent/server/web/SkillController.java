package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ParsedSkillPackage;
import com.cmagent.server.service.SkillManagementService;
import com.cmagent.server.service.SkillPackageParser;
import com.cmagent.server.service.SkillQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 技能管理接口；权限与租户均从当前 JWT 主体取得。 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillPackageParser parser;
    private final SkillProperties properties;
    private final SkillManagementService management;
    private final SkillQueryService queries;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;

    /** 创建技能控制器并保存解析、服务、权限和审计依赖。 */
    public SkillController(SkillPackageParser parser, SkillProperties properties,
                           SkillManagementService management, SkillQueryService queries,
                           PermissionEvaluator permissions, AuditAppender audit) {
        this.parser = parser;
        this.properties = properties;
        this.management = management;
        this.queries = queries;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 返回公开能力和限制；功能关闭时仍可用于控制台降级。 */
    @GetMapping("/capabilities")
    public SkillResponses.Capabilities capabilities(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", "capabilities");
        return new SkillResponses.Capabilities(properties.isEnabled(),
                List.of(".md", ".txt", ".json", ".yaml", ".yml", ".csv"),
                properties.getMaxZipBytes(), properties.getMaxExpandedBytes(), properties.getMaxFiles(),
                properties.getMaxInstructionBytes(), properties.getMaxResourceBytes(),
                properties.getMaxPathLength(), properties.getMaxBoundSkills());
    }

    /** 分页查询当前租户技能。 */
    @GetMapping
    public ApiPageResponse<SkillResponses.Summary> list(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", "list");
        return queries.list(principal, q, enabled, new ApiPageRequest(page, size));
    }

    /** 创建默认停用的技能。 */
    @PostMapping
    public ResponseEntity<SkillResponses.Detail> create(
            @RequestPart("file") MultipartFile file, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", "create");
        ParsedSkillPackage parsed = parse(file);
        SkillVersionView created = management.create(principal, parsed);
        return ResponseEntity.status(HttpStatus.CREATED).body(queries.detail(principal, created.definition()));
    }

    /** 查询当前版本详情。 */
    @GetMapping("/{id}")
    public SkillResponses.Detail detail(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", id.toString());
        return queries.detail(principal, id);
    }

    /** 上传新版本；相同摘要返回 200，新版本返回 201。 */
    @PostMapping("/{id}/versions")
    public ResponseEntity<SkillResponses.Detail> update(
            @PathVariable("id") UUID id,
            @RequestParam("expectedVersionId") UUID expectedVersionId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", id.toString());
        SkillVersionView result = management.update(principal, id, expectedVersionId, parse(file));
        HttpStatus status = result.version().id().equals(expectedVersionId) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(queries.detail(principal, result.definition()));
    }

    /** 显式启用或停用技能。 */
    @PutMapping("/{id}/enabled")
    public SkillDefinition enabled(@PathVariable("id") UUID id,
                                   @Valid @RequestBody SkillRequests.EnabledRequest request,
                                   Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", id.toString());
        return management.setEnabled(principal, id, request.enabled());
    }

    /** 查看固定版本的指令或文本资源。 */
    @GetMapping("/{id}/versions/{versionId}/resources")
    public SkillResponses.Resource resource(@PathVariable("id") UUID id,
                                            @PathVariable("versionId") UUID versionId,
                                            @RequestParam("path") String path,
                                            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", id.toString());
        return queries.resource(principal, id, versionId, path);
    }

    private ParsedSkillPackage parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SkillAccessException(ApiErrorCode.SKILL_PACKAGE_INVALID,
                    "请选择非空技能 ZIP", UUID.randomUUID().toString(), false);
        }
        try {
            return parser.parse(file.getInputStream(), properties.toPackageLimits());
        } catch (IOException ex) {
            throw new SkillAccessException(ApiErrorCode.SKILL_PACKAGE_INVALID,
                    "无法读取技能 ZIP", UUID.randomUUID().toString(), false);
        }
    }

    private PrincipalRef principal(Authentication authentication) {
        // tenant 和权限只接受 JWT 过滤器创建的会话主体，不能信任 multipart 或查询参数。
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
