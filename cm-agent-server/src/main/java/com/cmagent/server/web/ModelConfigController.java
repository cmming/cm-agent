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
import com.cmagent.server.service.ModelCatalogDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ModelCatalogDiscoveryService catalogDiscoveryService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public ModelConfigController(
            ModelConfigRepository repository,
            ModelConfigCommandService commandService,
            ModelCatalogDiscoveryService catalogDiscoveryService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender
    ) {
        this.repository = repository;
        this.commandService = commandService;
        this.catalogDiscoveryService = catalogDiscoveryService;
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

    /**
     * 使用创建前或修改后的表单草稿获取可选择的模型名称。
     *
     * <p>API Key 只在本次服务端出站调用中使用；浏览器不会直接访问模型供应商，也不会收到密钥、
     * 请求头或原始供应商响应。</p>
     *
     * @param request 已完成字段校验的草稿连接信息
     * @param authentication 由 JWT 过滤器建立的可信认证会话
     * @param servletRequest 当前请求，用于取得可关联的 errorId
     * @return 已过滤、去重且可作为模型名称选择的目录响应
     */
    @PostMapping("/discover-models")
    public ModelCatalogResponse discoverModels(
            @Valid @RequestBody ModelCatalogDiscoveryRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:write", "discover-draft");
        return new ModelCatalogResponse(catalogDiscoveryService.discoverDraft(
                principal, request.providerType(), request.baseUrl(), request.apiKey(),
                RequestCorrelationFilter.errorIdOf(servletRequest)
        ));
    }

    /**
     * 使用当前租户内已保存配置的密文获取模型名称。
     *
     * <p>请求不接收客户端传入的地址或 Provider，确保已保存密钥只能发送至该配置原有的受控目标。</p>
     *
     * @param id 当前 tenant 内的模型配置标识
     * @param authentication 由 JWT 过滤器建立的可信认证会话
     * @param servletRequest 当前请求，用于取得可关联的 errorId
     * @return 已过滤、去重且可作为模型名称选择的目录响应
     */
    @PostMapping("/{id}/discover-models")
    public ModelCatalogResponse discoverSavedModels(
            @PathVariable("id") UUID id,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "model:write", id.toString());
        return new ModelCatalogResponse(catalogDiscoveryService.discoverSaved(
                principal, id, RequestCorrelationFilter.errorIdOf(servletRequest)
        ));
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

    /**
     * 创建前模型目录发现的临时请求。
     *
     * @param providerType 决定使用的模型目录协议
     * @param baseUrl 仅本次请求使用的模型服务基础地址
     * @param apiKey 仅本次服务端请求使用的 API Key，不会被保存或返回
     */
    public record ModelCatalogDiscoveryRequest(
            @NotNull ModelProviderType providerType,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 2048) String apiKey
    ) {
    }

    /**
     * 模型目录的受控响应。
     *
     * @param items 已过滤、去重且可作为 modelName 保存的模型名称
     */
    public record ModelCatalogResponse(List<String> items) {
        public ModelCatalogResponse {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }
}
