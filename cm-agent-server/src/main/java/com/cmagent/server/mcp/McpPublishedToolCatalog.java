package com.cmagent.server.mcp;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.runtime.ToolPreparationDataAccessException;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class McpPublishedToolCatalog {
    static final String INVOKE_PERMISSION = "tool:mcp:invoke";
    private static final String RESOURCE_TYPE = "TOOL";
    private static final String TOOL_UNAVAILABLE = "工具不可用";
    private static final String TOOL_EXECUTION_FAILED = "工具执行失败";

    private final ToolDefinitionRepository tools;
    private final HttpToolConfigRepository httpConfigs;
    private final McpToolPublicationRepository publications;
    private final ToolRegistry registry;
    private final GovernedToolExecutionService executions;
    private final PermissionEvaluator permissions;
    private final AuditAppender audits;
    private final ObjectMapper objectMapper;
    private final ToolOutputSanitizer sanitizer;

    public McpPublishedToolCatalog(
            ToolDefinitionRepository tools,
            HttpToolConfigRepository httpConfigs,
            McpToolPublicationRepository publications,
            ToolRegistry registry,
            GovernedToolExecutionService executions,
            PermissionEvaluator permissions,
            AuditAppender audits,
            ObjectMapper objectMapper,
            ToolOutputSanitizer sanitizer
    ) {
        this.tools = Objects.requireNonNull(tools, "tools 不能为空");
        this.httpConfigs = Objects.requireNonNull(httpConfigs, "httpConfigs 不能为空");
        this.publications = Objects.requireNonNull(publications, "publications 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.executions = Objects.requireNonNull(executions, "executions 不能为空");
        this.permissions = Objects.requireNonNull(permissions, "permissions 不能为空");
        this.audits = Objects.requireNonNull(audits, "audits 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer 不能为空");
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications(PrincipalRef principal) {
        Objects.requireNonNull(principal, "principal 不能为空");
        List<ToolDefinition> publishedTools = publications.listEnabledByTenant(principal.tenantId()).stream()
                .map(publication -> currentPublishableTool(principal.tenantId(), publication.toolId()).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ToolDefinition::name).thenComparing(tool -> tool.id().toString()))
                .toList();
        rejectDuplicateNames(publishedTools);
        return publishedTools.stream().map(tool -> specification(principal, tool)).toList();
    }

    private McpStatelessServerFeatures.SyncToolSpecification specification(PrincipalRef principal, ToolDefinition tool) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(readSchema(tool.inputSchema()))
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler((context, request) -> call(principal, tool, request))
                .build();
    }

    private McpSchema.CallToolResult call(
            PrincipalRef principal,
            ToolDefinition listedTool,
            McpSchema.CallToolRequest call
    ) {
        AuthorizationDecision decision = permissions.check(principal, INVOKE_PERMISSION);
        if (!decision.allowed()) {
            audits.accessDenied(principal, "MCP", "/mcp", INVOKE_PERMISSION, decision.reason());
            return failed("没有权限调用 MCP 工具");
        }
        ToolDefinition current = currentPublishedTool(principal, listedTool).orElse(null);
        if (current == null) {
            return failed(TOOL_UNAVAILABLE);
        }
        String inputJson;
        try {
            inputJson = canonicalJson(call.arguments() == null ? Map.of() : call.arguments());
        } catch (JsonProcessingException exception) {
            return failed("工具输入无效");
        }
        ToolExecutionRequest request = new ToolExecutionRequest(
                principal.tenantId(), null, principal, null, UUID.randomUUID().toString(), current.id(), inputJson,
                ToolInvocationSource.MCP
        );
        try {
            ToolExecutionResult result = executions.executeWhenReady(current, request, () -> audits.append(
                    principal.tenantId(), principal.principalId(), "MCP_TOOL_CALL_STARTED", RESOURCE_TYPE,
                    current.id().toString(), "RUNNING", "MCP 工具调用已开始"
            ));
            if (result.success()) {
                audits.append(principal.tenantId(), principal.principalId(), "MCP_TOOL_CALL_COMPLETED", RESOURCE_TYPE,
                        current.id().toString(), "SUCCEEDED", "MCP 工具调用完成");
                return succeeded(sanitizer.sanitize(result.outputSummary(), List.of()));
            }
            audits.append(principal.tenantId(), principal.principalId(), "MCP_TOOL_CALL_FAILED", RESOURCE_TYPE,
                    current.id().toString(), "FAILED", "MCP 工具调用失败");
            return failed(TOOL_EXECUTION_FAILED);
        } catch (AuditPersistenceException exception) {
            throw protocolPersistenceError();
        } catch (ToolPreparationDataAccessException exception) {
            throw protocolPersistenceError();
        } catch (RuntimeException exception) {
            audits.append(principal.tenantId(), principal.principalId(), "MCP_TOOL_CALL_FAILED", RESOURCE_TYPE,
                    current.id().toString(), "FAILED", "MCP 工具调用失败");
            return failed(TOOL_EXECUTION_FAILED);
        }
    }

    private Optional<ToolDefinition> currentPublishedTool(PrincipalRef principal, ToolDefinition listedTool) {
        McpToolPublication publication = publications
                .findByTenantAndToolId(principal.tenantId(), listedTool.id())
                .filter(McpToolPublication::enabled)
                .orElse(null);
        if (publication == null) {
            return Optional.empty();
        }
        return currentPublishableTool(principal.tenantId(), listedTool.id())
                .filter(current -> current.name().equals(listedTool.name()));
    }

    private Optional<ToolDefinition> currentPublishableTool(UUID tenantId, UUID toolId) {
        return tools.findByTenantAndId(tenantId, toolId)
                .filter(tool -> tool.enabled() && tenantId.equals(tool.tenantId()) && toolId.equals(tool.id()))
                .filter(this::hasMatchingRuntime);
    }

    private boolean hasMatchingRuntime(ToolDefinition tool) {
        if (tool.type() == ToolType.HTTP) {
            HttpToolConfig config = httpConfigs.findByTenantAndToolId(tool.tenantId(), tool.id()).orElse(null);
            return config != null && tool.endpoint() != null && tool.endpoint().equals(config.urlTemplate());
        }
        if (tool.type() == ToolType.LOCAL) {
            return registry.snapshot(tool.id())
                    .map(ToolRegistry.ToolRegistrationSnapshot::definition)
                    .filter(tool::equals)
                    .isPresent();
        }
        return false;
    }

    private Map<String, Object> readSchema(String schema) {
        try {
            return objectMapper.readValue(schema, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("MCP 工具输入 Schema 无效");
        }
    }

    private String canonicalJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(canonicalize(objectMapper.valueToTree(value)));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            fields.stream().sorted(Map.Entry.comparingByKey())
                    .forEach(field -> canonical.set(field.getKey(), canonicalize(field.getValue())));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            value.forEach(child -> canonical.add(canonicalize(child)));
            return canonical;
        }
        return value;
    }

    private void rejectDuplicateNames(List<ToolDefinition> publishedTools) {
        Set<String> names = new HashSet<>();
        if (publishedTools.stream().anyMatch(tool -> !names.add(tool.name()))) {
            throw new IllegalStateException("MCP 工具名称冲突");
        }
    }

    private McpSchema.CallToolResult succeeded(String output) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(output)))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult failed(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message)))
                .isError(true)
                .build();
    }

    private McpError protocolPersistenceError() {
        return McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                .message("MCP 工具调用暂不可用")
                .build();
    }
}
