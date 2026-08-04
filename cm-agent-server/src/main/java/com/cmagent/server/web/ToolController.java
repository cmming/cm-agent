package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ManagementCommandService;
import com.cmagent.server.service.HttpToolCreateSpec;
import com.cmagent.server.service.ToolQueryService;
import com.cmagent.server.service.ToolSummary;
import com.cmagent.server.service.ToolDebugResponse;
import com.cmagent.server.service.ToolDebugService;
import com.cmagent.server.service.McpPublicationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/tools")
/** 工具治理接口，集中处理工具管理、调试和 MCP 发布等 HTTP 入口。 */
public class ToolController {
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final ManagementCommandService managementCommandService;
    private final ObjectMapper objectMapper;
    private final ToolQueryService toolQueryService;
    private final ToolDebugService toolDebugService;
    private final McpPublicationService mcpPublicationService;
    /**
     * 创建 {@code ToolController} 实例并保存其运行所需依赖。
     *
     * @param permissionEvaluator 执行主体权限判断的组件。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param managementCommandService 负责当前业务流程的服务。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param toolQueryService 负责当前业务流程的服务。
     * @param toolDebugService 负责当前业务流程的服务。
     * @param mcpPublicationService 负责当前业务流程的服务。
     */
    public ToolController(
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            ManagementCommandService managementCommandService,
            ObjectMapper objectMapper,
            ToolQueryService toolQueryService,
            ToolDebugService toolDebugService,
            McpPublicationService mcpPublicationService
    ) {
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.managementCommandService = managementCommandService;
        this.objectMapper = objectMapper;
        this.toolQueryService = toolQueryService;
        this.toolDebugService = toolDebugService;
        this.mcpPublicationService = mcpPublicationService;
    }

    /**
     * 查询当前租户可见的工具摘要。
     *
     * @param authentication 当前请求认证信息
     * @return 工具摘要列表，不包含敏感 secret 原文
     * @throws ResponseStatusException 未认证或缺少 {@code tool:read} 权限时抛出
     */
    @GetMapping
    public List<ToolSummaryResponse> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "TOOL", "list");
        return toolQueryService.listByTenant(principal.tenantId()).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 创建工具及可选的 HTTP 配置或 MCP 发布记录。
     *
     * @param request        工具创建请求
     * @param authentication 当前请求认证信息
     * @return 创建后的工具摘要
     * @throws ResponseStatusException 未认证、缺少权限或配置不合法时抛出
     */
    @PostMapping
    public ToolSummaryResponse create(@Valid @RequestBody ToolCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", "create");
        ToolDefinition created = managementCommandService.createTool(
                principal,
                request.name(),
                request.description(),
                request.type(),
                request.riskLevel(),
                toHttpToolCreateSpec(request.httpConfig()),
                Boolean.TRUE.equals(request.mcpPublished())
        );
        return toolQueryService.findByTenantAndId(principal.tenantId(), created.id())
                .map(this::toSummary)
                .orElseThrow(() -> new IllegalStateException("已创建工具未找到"));
    }

    /**
     * 更新当前租户中的工具定义及其附属配置。
     *
     * @param id             工具标识
     * @param request        工具更新请求
     * @param authentication 当前请求认证信息
     * @return 更新后的工具摘要
     * @throws ResponseStatusException 未认证、无权限、资源不存在或名称冲突时抛出
     */
    @PutMapping("/{id}")
    public ToolSummaryResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ToolUpdateRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        ManagementCommandService.ToolUpdateResult updated = managementCommandService.updateTool(
                principal,
                id,
                new ManagementCommandService.ToolUpdateSpec(
                        request.name(),
                        request.description(),
                        request.type(),
                        request.riskLevel(),
                        request.enabled(),
                        toHttpToolCreateSpec(request.httpConfig()),
                        Boolean.TRUE.equals(request.mcpPublished())
                )
        );
        return toSummary(toolQueryService.summarize(
                updated.tool(),
                updated.httpToolConfig(),
                updated.mcpPublished()
        ));
    }

    /**
     * 删除当前租户中未被 Agent 引用的工具。
     *
     * @param id             工具标识
     * @param authentication 当前请求认证信息
     * @return 无内容响应
     * @throws ResponseStatusException 未认证、无权限、资源不存在或工具仍被引用时抛出
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:delete", "TOOL", id.toString());
        managementCommandService.deleteTool(principal, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 将工具授权给指定 Agent。
     *
     * @param id             工具标识
     * @param request        授权目标 Agent
     * @param authentication 当前请求认证信息
     * @return 新建的工具授权记录
     * @throws ResponseStatusException 未认证、无权限或资源不存在时抛出
     */
    @PostMapping("/{id}/grants")
    public ToolGrant grant(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ToolGrantRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        return managementCommandService.grantTool(principal, id, request.agentId());
    }

    /**
     * 解除指定 Agent 与工具的关联。
     *
     * @param id             工具标识
     * @param agentId        Agent 标识
     * @param authentication 当前请求认证信息
     * @return 更新后的 Agent
     * @throws ResponseStatusException 未认证、无权限或资源不存在时抛出
     */
    @DeleteMapping("/{id}/grants/{agentId}")
    public AgentDefinition revoke(
            @PathVariable("id") UUID id,
            @PathVariable("agentId") UUID agentId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        return managementCommandService.revokeTool(principal, id, agentId);
    }

    /**
     * 调试工具并返回脱敏后的执行结果。
     *
     * @param id             工具标识
     * @param request        调试输入及高风险工具二次确认信息
     * @param authentication 当前请求认证信息
     * @return 脱敏后的调试结果
     * @throws ResponseStatusException 未认证、无权限、确认失败或工具执行失败时抛出
     */
    @PostMapping("/{id}/debug")
    public ToolDebugResponse debug(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ToolDebugRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:debug", "TOOL", id.toString());
        return toolDebugService.debug(principal, id, canonicalJson(request.input()), request.confirmedToolName());
    }

    /**
     * 发布工具到当前租户的 MCP 工具目录。
     *
     * @param id             工具标识
     * @param authentication 当前请求认证信息
     * @return MCP 发布记录
     * @throws ResponseStatusException 未认证、无权限或工具不满足发布规则时抛出
     */
    @PutMapping("/{id}/mcp-publication")
    public com.cmagent.core.domain.McpToolPublication publishMcpTool(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        return mcpPublicationService.publish(principal, id);
    }

    /**
     * 从当前租户的 MCP 工具目录取消发布。
     *
     * @param id             工具标识
     * @param authentication 当前请求认证信息
     * @return 无内容响应
     * @throws ResponseStatusException 未认证、无权限或工具不存在时抛出
     */
    @DeleteMapping("/{id}/mcp-publication")
    public ResponseEntity<Void> unpublishMcpTool(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());
        mcpPublicationService.unpublish(principal, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 从 Spring Security 认证对象提取可信主体上下文。
     *
     * @param authentication Spring Security 认证信息，用于解析当前登录主体。
     */
    private PrincipalRef principal(Authentication authentication) {
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
     * 将工具查询模型转换为不含 Secret 原文的响应摘要。
     *
     * @param summary 内部工具查询摘要。
     */
    private ToolSummaryResponse toSummary(ToolSummary summary) {
        var tool = summary.tool();
        return new ToolSummaryResponse(
                tool.id(), tool.tenantId(), tool.name(), tool.description(), tool.type(), tool.inputSchema(),
                tool.riskLevel(), tool.enabled(), tool.endpoint(), tool.createdBy(), tool.updatedBy(),
                summary.httpConfig() == null ? null : toHttpConfigResponse(summary.httpConfig()), summary.mcpPublished(),
                summary.runtimeReady()
        );
    }

    /**
     * 将 HTTP 请求 DTO 转换为服务层创建规格。
     *
     * @param request 客户端提交的动态 HTTP 工具配置 DTO
     */
    private HttpToolCreateSpec toHttpToolCreateSpec(HttpConfigRequest request) {
        if (request == null) {
            return null;
        }
        List<HttpParameterMapping> mappings = request.parameterMappings() == null ? List.of() : request.parameterMappings().stream()
                                                                                                .map(mapping -> new HttpParameterMapping(
                                                                                                        mapping.sourcePointer(),
                                                                                                        mapping.location(),
                                                                                                        mapping.targetName(),
                                                                                                        mapping.targetPointer(),
                                                                                                        mapping.required(),
                                                                                                        mapping.defaultValue() == null ? "" : canonicalJson(mapping.defaultValue())
                                                                                                ))
                                                                                                .toList();
        return new HttpToolCreateSpec(
                request.method(),
                request.urlTemplate(),
                canonicalJson(request.inputSchema()),
                mappings,
                request.secretHeaders() == null ? Map.of() : Map.copyOf(request.secretHeaders()),
                java.time.Duration.ofMillis(request.timeoutMillis())
        );
    }

    /**
     * 将 HTTP 配置领域对象转换为响应 DTO。
     *
     * @param config 待转换为响应 DTO 的动态 HTTP 工具配置
     */
    private HttpToolConfigResponse toHttpConfigResponse(com.cmagent.core.domain.HttpToolConfig config) {
        return new HttpToolConfigResponse(
                config.method(), config.urlTemplate(), config.inputSchema(), config.parameterMappings(),
                config.secretHeaders(), config.timeout().toMillis()
        );
    }

    /**
     * 将 JSON 节点转换为字段顺序稳定的规范文本。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON 配置序列化失败", exception);
        }
    }

    /**
     * 递归规范化 JSON 对象的字段顺序。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, child) -> canonical.set(name, canonicalize(child)));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            value.forEach(child -> canonical.add(canonicalize(child)));
            return canonical;
        }
        return value.deepCopy();
    }

    /**
     * 封装 {@code ToolCreateRequest} 在当前流程中使用的不可变数据。
     */
    public record ToolCreateRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull ToolType type,
            @NotNull ToolRiskLevel riskLevel,
            Boolean mcpPublished,
            @Valid HttpConfigRequest httpConfig
    ) {
    }

    /**
     * ToolUpdateRequest：工具更新请求。
     */
    public record ToolUpdateRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull ToolType type,
            @NotNull ToolRiskLevel riskLevel,
            @NotNull Boolean enabled,
            Boolean mcpPublished,
            @Valid HttpConfigRequest httpConfig
    ) {
    }

    /**
     * 封装 {@code HttpConfigRequest} 在当前流程中使用的不可变数据。
     */
    public record HttpConfigRequest(
            @NotNull HttpToolMethod method,
            @NotBlank String urlTemplate,
            @NotNull JsonNode inputSchema,
            List<@NotNull @Valid HttpParameterMappingRequest> parameterMappings,
            Map<@NotBlank String, @NotBlank String> secretHeaders,
            @NotNull @Min(100) @Max(30000) Long timeoutMillis
    ) {
    }

    /**
     * 封装 {@code HttpParameterMappingRequest} 在当前流程中使用的不可变数据。
     */
    public record HttpParameterMappingRequest(
            String sourcePointer,
            @NotNull HttpParameterLocation location,
            String targetName,
            String targetPointer,
            boolean required,
            JsonNode defaultValue
    ) {
    }

    /**
     * 封装 {@code HttpToolConfigResponse} 在当前流程中使用的不可变数据。
     */
    public record HttpToolConfigResponse(
            HttpToolMethod method,
            String urlTemplate,
            String inputSchema,
            List<HttpParameterMapping> parameterMappings,
            Map<String, String> secretHeaders,
            long timeoutMillis
    ) {
    }

    /**
     * 封装 {@code ToolSummaryResponse} 在当前流程中使用的不可变数据。
     */
    public record ToolSummaryResponse(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            ToolType type,
            String inputSchema,
            ToolRiskLevel riskLevel,
            boolean enabled,
            String endpoint,
            String createdBy,
            String updatedBy,
            HttpToolConfigResponse httpConfig,
            boolean mcpPublished,
            boolean runtimeReady
    ) {
    }

    /**
     * 封装 {@code ToolGrantRequest} 在当前流程中使用的不可变数据。
     */
    public record ToolGrantRequest(@NotNull UUID agentId) {
    }

    /**
     * 封装 {@code ToolDebugRequest} 在当前流程中使用的不可变数据。
     */
    public record ToolDebugRequest(@NotNull JsonNode input, String confirmedToolName) {
    }
}
