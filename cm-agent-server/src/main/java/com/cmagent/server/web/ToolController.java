package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ManagementCommandService;
import com.cmagent.server.service.HttpToolCreateSpec;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
public class ToolController {
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    private final ManagementCommandService managementCommandService;
    private final ObjectMapper objectMapper;

    public ToolController(
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender,
            ManagementCommandService managementCommandService,
            ObjectMapper objectMapper
    ) {
        this.toolRepository = toolRepository;
        this.httpToolConfigRepository = httpToolConfigRepository;
        this.mcpToolPublicationRepository = mcpToolPublicationRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
        this.managementCommandService = managementCommandService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<ToolSummaryResponse> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "TOOL", "list");
        return toolRepository.listByTenant(principal.tenantId()).stream()
                .map(tool -> toSummary(principal, tool))
                .toList();
    }

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
        return toSummary(principal, created);
    }

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

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    private ToolSummaryResponse toSummary(PrincipalRef principal, ToolDefinition tool) {
        HttpToolConfigResponse httpConfig = httpToolConfigRepository
                .findByTenantAndToolId(principal.tenantId(), tool.id())
                .map(this::toHttpConfigResponse)
                .orElse(null);
        boolean mcpPublished = mcpToolPublicationRepository
                .findByTenantAndToolId(principal.tenantId(), tool.id())
                .map(publication -> publication.enabled())
                .orElse(false);
        return new ToolSummaryResponse(
                tool.id(), tool.tenantId(), tool.name(), tool.description(), tool.type(), tool.inputSchema(),
                tool.riskLevel(), tool.enabled(), tool.endpoint(), tool.createdBy(), tool.updatedBy(), httpConfig, mcpPublished
        );
    }

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

    private HttpToolConfigResponse toHttpConfigResponse(HttpToolConfig config) {
        return new HttpToolConfigResponse(
                config.method(), config.urlTemplate(), config.inputSchema(), config.parameterMappings(),
                config.secretHeaders(), config.timeout().toMillis()
        );
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON 配置序列化失败", exception);
        }
    }

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

    public record ToolCreateRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull ToolType type,
            @NotNull ToolRiskLevel riskLevel,
            Boolean mcpPublished,
            @Valid HttpConfigRequest httpConfig
    ) {
    }

    public record HttpConfigRequest(
            @NotNull HttpToolMethod method,
            @NotBlank String urlTemplate,
            @NotNull JsonNode inputSchema,
            @Valid List<HttpParameterMappingRequest> parameterMappings,
            Map<String, String> secretHeaders,
            @NotNull @Min(100) @Max(30000) Long timeoutMillis
    ) {
    }

    public record HttpParameterMappingRequest(
            String sourcePointer,
            @NotNull HttpParameterLocation location,
            String targetName,
            String targetPointer,
            boolean required,
            JsonNode defaultValue
    ) {
    }

    public record HttpToolConfigResponse(
            HttpToolMethod method,
            String urlTemplate,
            String inputSchema,
            List<HttpParameterMapping> parameterMappings,
            Map<String, String> secretHeaders,
            long timeoutMillis
    ) {
    }

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
            boolean mcpPublished
    ) {
    }

    public record ToolGrantRequest(@NotNull UUID agentId) {
    }
}
