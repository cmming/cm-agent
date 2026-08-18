package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ModelConfigCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 模型配置管理接口；API Key 仅允许写入，所有访问均限定在认证主体所属租户。 */
@RestController
@RequestMapping("/api/model-configs")
public class ModelConfigController {
    private final ModelConfigRepository repository;
    private final ModelConfigCommandService commandService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public ModelConfigController(
            ModelConfigRepository repository,
            ModelConfigCommandService commandService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender
    ) {
        this.repository = repository;
        this.commandService = commandService;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    /** 列出当前租户模型配置，响应中不会出现凭据字段。 */
    @GetMapping
    public List<ModelConfig> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:read", "list");
        return repository.listByTenant(principal.tenantId());
    }

    /** 查询当前租户单个模型配置。 */
    @GetMapping("/{id}")
    public ModelConfig get(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:read", id.toString());
        return repository.findByTenantAndId(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
    }

    /** 创建模型配置并写入 API Key 密文。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelConfig create(@Valid @RequestBody ModelConfigCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:write", "create");
        return commandService.create(
                principal, request.providerType(), request.displayName(), request.baseUrl(),
                request.modelName(), request.enabled(), request.apiKey()
        );
    }

    /** 更新模型配置全部可编辑元数据；请求提供 API Key 时轮换密钥。 */
    @PutMapping("/{id}")
    public ModelConfig update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ModelConfigUpdateRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:write", id.toString());
        return commandService.update(
                principal, id, request.providerType(), request.displayName(), request.baseUrl(),
                request.modelName(), request.enabled(), request.apiKey()
        );
    }

    /** 删除未被 Agent 引用的模型配置。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:delete", id.toString());
        commandService.delete(principal, id);
        return ResponseEntity.noContent().build();
    }

    private PrincipalRef principal(Authentication authentication) {
        // tenant 和权限只接受 JWT 过滤器建立的可信会话，不能由请求体覆盖。
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(
                session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions())
        );
    }

    private void authorize(PrincipalRef principal, String permission, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, "MODEL_CONFIG", resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /** 创建模型配置时的写入请求；API Key 不会出现在响应、日志或审计记录中。 */
    public record ModelConfigCreateRequest(
            @NotNull ModelProviderType providerType,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 160) String modelName,
            boolean enabled,
            @NotBlank @Size(max = 2048) String apiKey
    ) {
    }

    /** 更新模型配置时的写入请求；空缺 API Key 表示保留当前密文。 */
    public record ModelConfigUpdateRequest(
            @NotNull ModelProviderType providerType,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 160) String modelName,
            boolean enabled,
            @Pattern(regexp = ".*\\S.*") @Size(max = 2048) String apiKey
    ) {
    }
}
