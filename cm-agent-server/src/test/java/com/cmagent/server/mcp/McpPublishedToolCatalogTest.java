package com.cmagent.server.mcp;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
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
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpPublishedToolCatalogTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID HTTP_ID = UUID.fromString("10000000-0000-0000-0000-000000000801");
    private static final UUID LOCAL_ID = UUID.fromString("10000000-0000-0000-0000-000000000802");

    @Mock
    private ToolDefinitionRepository tools;
    @Mock
    private HttpToolConfigRepository httpConfigs;
    @Mock
    private McpToolPublicationRepository publications;
    @Mock
    private ToolRegistry registry;
    @Mock
    private GovernedToolExecutionService executions;
    @Mock
    private PermissionEvaluator permissions;
    @Mock
    private AuditAppender audits;

    private ObjectMapper objectMapper;
    private McpPublishedToolCatalog catalog;
    private PrincipalRef principal;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        catalog = new McpPublishedToolCatalog(
                tools, httpConfigs, publications, registry, executions, permissions, audits,
                objectMapper, new ToolOutputSanitizer(objectMapper)
        );
        principal = new PrincipalRef(TENANT, "mcp-user", "MCP 用户", Set.of("tool:mcp:invoke"));
    }

    @Test
    void list只返回当前租户已发布启用且配置一致的Http和Local并按名称排序() {
        ToolDefinition http = tool(HTTP_ID, TENANT, "z_http", ToolType.HTTP, true, "https://api.example.test/run");
        ToolDefinition local = tool(LOCAL_ID, TENANT, "a_local", ToolType.LOCAL, true, "");
        UUID disabledId = UUID.fromString("10000000-0000-0000-0000-000000000803");
        ToolDefinition disabled = tool(disabledId, TENANT, "disabled", ToolType.LOCAL, false, "");
        UUID foreignId = UUID.fromString("10000000-0000-0000-0000-000000000804");
        when(publications.listEnabledByTenant(TENANT)).thenReturn(List.of(
                publication(HTTP_ID, TENANT), publication(LOCAL_ID, TENANT),
                publication(disabledId, TENANT), publication(foreignId, TENANT)
        ));
        when(tools.findByTenantAndId(TENANT, HTTP_ID)).thenReturn(Optional.of(http));
        when(tools.findByTenantAndId(TENANT, LOCAL_ID)).thenReturn(Optional.of(local));
        when(tools.findByTenantAndId(TENANT, disabledId)).thenReturn(Optional.of(disabled));
        when(tools.findByTenantAndId(TENANT, foreignId)).thenReturn(Optional.empty());
        when(httpConfigs.findByTenantAndToolId(TENANT, HTTP_ID)).thenReturn(Optional.of(httpConfig(http)));
        when(registry.snapshot(LOCAL_ID)).thenReturn(Optional.of(
                new ToolRegistry.ToolRegistrationSnapshot(local, request -> ToolExecutionResult.succeeded("ok", null))
        ));

        assertThat(catalog.specifications(principal))
                .extracting(specification -> specification.tool().name())
                .containsExactly("a_local", "z_http");
        verify(publications).listEnabledByTenant(TENANT);
        verify(publications, never()).listEnabledByTenant(OTHER_TENANT);
    }

    @Test
    void list拒绝同一租户重复发布名称而不是静默覆盖() {
        ToolDefinition first = tool(HTTP_ID, TENANT, "duplicate", ToolType.HTTP, true, "https://one.example.test");
        ToolDefinition second = tool(LOCAL_ID, TENANT, "duplicate", ToolType.LOCAL, true, "");
        when(publications.listEnabledByTenant(TENANT)).thenReturn(List.of(publication(HTTP_ID, TENANT), publication(LOCAL_ID, TENANT)));
        when(tools.findByTenantAndId(TENANT, HTTP_ID)).thenReturn(Optional.of(first));
        when(tools.findByTenantAndId(TENANT, LOCAL_ID)).thenReturn(Optional.of(second));
        when(httpConfigs.findByTenantAndToolId(TENANT, HTTP_ID)).thenReturn(Optional.of(httpConfig(first)));
        when(registry.snapshot(LOCAL_ID)).thenReturn(Optional.of(
                new ToolRegistry.ToolRegistrationSnapshot(second, request -> ToolExecutionResult.succeeded("ok", null))
        ));

        assertThatThrownBy(() -> catalog.specifications(principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP 工具名称冲突");
    }

    @Test
    void call每次重读发布和工具并使用CanonicalJson与Mcp执行来源() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.of(publication(LOCAL_ID, TENANT)));
        when(executions.executeWhenReady(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded("{\"safe\":true}", 200);
        });
        var specification = catalog.specifications(principal).getFirst();

        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY,
                new McpSchema.CallToolRequest("echo", Map.of("z", 1, "a", Map.of("y", 2, "x", 1)))
        );

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                McpSchema.TextContent.class,
                content -> assertThat(content.text()).isEqualTo("{\"safe\":true}")
        );
        ArgumentCaptor<ToolExecutionRequest> request = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(executions).executeWhenReady(any(), request.capture(), any());
        assertThat(request.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(request.getValue().agentId()).isNull();
        assertThat(request.getValue().runId()).isNull();
        assertThat(request.getValue().source()).isEqualTo(ToolInvocationSource.MCP);
        assertThat(request.getValue().inputJson()).isEqualTo("{\"a\":{\"x\":1,\"y\":2},\"z\":1}");
        verify(publications).findByTenantAndToolId(TENANT, LOCAL_ID);
        verify(audits).append(TENANT, "mcp-user", "MCP_TOOL_CALL_STARTED", "TOOL",
                LOCAL_ID.toString(), "RUNNING", "MCP 工具调用已开始");
        verify(audits).append(TENANT, "mcp-user", "MCP_TOOL_CALL_COMPLETED", "TOOL",
                LOCAL_ID.toString(), "SUCCEEDED", "MCP 工具调用完成");
    }

    @Test
    void call取消发布后即时不可用且不会执行() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.empty());

        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        );

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                McpSchema.TextContent.class,
                content -> assertThat(content.text()).isEqualTo("工具不可用")
        );
        verify(audits).append(TENANT, "mcp-user", "MCP_TOOL_CALL_FAILED", "TOOL",
                LOCAL_ID.toString(), "FAILED", "MCP 工具调用失败");
        verify(executions, never()).executeWhenReady(any(), any(), any());
    }

    @Test
    void call工具禁用后即时不可用并记录失败审计() {
        ToolDefinition enabled = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        ToolDefinition disabled = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, false, "");
        publishableLocal(enabled);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.of(publication(LOCAL_ID, TENANT)));
        when(tools.findByTenantAndId(TENANT, LOCAL_ID)).thenReturn(Optional.of(disabled));

        assertUnavailableAndAudited(specification);
    }

    @Test
    void callHttp端点漂移后即时不可用并记录失败审计() {
        ToolDefinition http = tool(HTTP_ID, TENANT, "http_echo", ToolType.HTTP, true, "https://api.example.test/run");
        publishableHttp(http);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, HTTP_ID)).thenReturn(Optional.of(publication(HTTP_ID, TENANT)));
        ToolDefinition drifted = tool(HTTP_ID, TENANT, "http_echo", ToolType.HTTP, true, "https://api.example.test/new");
        when(tools.findByTenantAndId(TENANT, HTTP_ID)).thenReturn(Optional.of(drifted));
        when(httpConfigs.findByTenantAndToolId(TENANT, HTTP_ID)).thenReturn(Optional.of(httpConfig(http)));

        assertUnavailableAndAudited(specification);
    }

    @Test
    void callLocal快照漂移后即时不可用并记录失败审计() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.of(publication(LOCAL_ID, TENANT)));
        ToolDefinition drifted = tool(LOCAL_ID, TENANT, "echo_v2", ToolType.LOCAL, true, "");
        when(registry.snapshot(LOCAL_ID)).thenReturn(Optional.of(
                new ToolRegistry.ToolRegistrationSnapshot(drifted, request -> ToolExecutionResult.succeeded("ok", null))
        ));

        assertUnavailableAndAudited(specification);
    }

    @Test
    void call不可用审计失败时返回受控协议错误且不会执行() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.empty());
        doThrow(auditFailure("Bearer unavailable-secret")).when(audits).append(
                TENANT, "mcp-user", "MCP_TOOL_CALL_FAILED", "TOOL", LOCAL_ID.toString(), "FAILED", "MCP 工具调用失败"
        );

        assertProtocolPersistenceError(() -> specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        ));
        verify(executions, never()).executeWhenReady(any(), any(), any());
    }

    @Test
    void call缺少权限写拒绝审计且不返回敏感错误() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.deny("Bearer secret-token"));

        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        );

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).doesNotContain("secret-token", "Bearer");
        verify(audits).accessDenied(principal, "MCP", "/mcp", "tool:mcp:invoke", "Bearer secret-token");
        verify(executions, never()).executeWhenReady(any(), any(), any());
    }

    @Test
    void call拒绝审计失败时返回受控协议错误且不泄露原因() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        var specification = catalog.specifications(principal).getFirst();
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.deny("Bearer denied-secret"));
        doThrow(auditFailure("Bearer denied-secret")).when(audits).accessDenied(
                principal, "MCP", "/mcp", "tool:mcp:invoke", "Bearer denied-secret"
        );

        assertProtocolPersistenceError(() -> specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        ));
        verify(executions, never()).executeWhenReady(any(), any(), any());
    }

    @Test
    void call运行时失败审计失败时返回受控协议错误且不泄露原因() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.of(publication(LOCAL_ID, TENANT)));
        when(executions.executeWhenReady(any(), any(), any())).thenThrow(new IllegalStateException("Bearer runtime-secret"));
        doThrow(auditFailure("Bearer runtime-secret")).when(audits).append(
                TENANT, "mcp-user", "MCP_TOOL_CALL_FAILED", "TOOL", LOCAL_ID.toString(), "FAILED", "MCP 工具调用失败"
        );
        var specification = catalog.specifications(principal).getFirst();

        assertProtocolPersistenceError(() -> specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        ));
    }

    @Test
    void call失败固定错误且成功输出移除SecretUrl和堆栈() {
        ToolDefinition local = tool(LOCAL_ID, TENANT, "echo", ToolType.LOCAL, true, "");
        publishableLocal(local);
        when(permissions.check(principal, "tool:mcp:invoke")).thenReturn(AuthorizationDecision.allow());
        when(publications.findByTenantAndToolId(TENANT, LOCAL_ID)).thenReturn(Optional.of(publication(LOCAL_ID, TENANT)));
        when(executions.executeWhenReady(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded(
                    "password=raw-secret https://private.example.test at com.example.Secret.run(Secret.java:1)", 200
            );
        });
        var specification = catalog.specifications(principal).getFirst();

        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest("echo", Map.of())
        );

        assertThat(result.isError()).isFalse();
        assertThat(result.content().toString()).doesNotContain("raw-secret", "https://", "Secret.java");
    }

    private void publishableLocal(ToolDefinition local) {
        when(publications.listEnabledByTenant(TENANT)).thenReturn(List.of(publication(LOCAL_ID, TENANT)));
        when(tools.findByTenantAndId(TENANT, LOCAL_ID)).thenReturn(Optional.of(local));
        when(registry.snapshot(LOCAL_ID)).thenReturn(Optional.of(
                new ToolRegistry.ToolRegistrationSnapshot(local, request -> ToolExecutionResult.succeeded("ok", null))
        ));
    }

    private void publishableHttp(ToolDefinition http) {
        when(publications.listEnabledByTenant(TENANT)).thenReturn(List.of(publication(HTTP_ID, TENANT)));
        when(tools.findByTenantAndId(TENANT, HTTP_ID)).thenReturn(Optional.of(http));
        when(httpConfigs.findByTenantAndToolId(TENANT, HTTP_ID)).thenReturn(Optional.of(httpConfig(http)));
    }

    private void assertUnavailableAndAudited(McpStatelessServerFeatures.SyncToolSpecification specification) {
        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest(specification.tool().name(), Map.of())
        );

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                McpSchema.TextContent.class,
                content -> assertThat(content.text()).isEqualTo("工具不可用")
        );
        verify(audits).append(TENANT, "mcp-user", "MCP_TOOL_CALL_FAILED", "TOOL",
                specification.tool().name().equals("http_echo") ? HTTP_ID.toString() : LOCAL_ID.toString(),
                "FAILED", "MCP 工具调用失败");
        verify(executions, never()).executeWhenReady(any(), any(), any());
    }

    private void assertProtocolPersistenceError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(McpError.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("MCP 工具调用暂不可用");
                    assertThat(error.getMessage()).doesNotContain("Bearer", "secret");
                });
    }

    private AuditPersistenceException auditFailure(String sensitiveReason) {
        return new AuditPersistenceException("审计写入失败：" + sensitiveReason, new IllegalStateException(sensitiveReason));
    }

    private static ToolDefinition tool(UUID id, UUID tenantId, String name, ToolType type, boolean enabled, String endpoint) {
        return new ToolDefinition(
                id, tenantId, name, "测试工具", type,
                "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}",
                ToolRiskLevel.LOW, enabled, endpoint, "admin", "admin"
        );
    }

    private static McpToolPublication publication(UUID toolId, UUID tenantId) {
        return new McpToolPublication(tenantId, toolId, true, "admin");
    }

    private static HttpToolConfig httpConfig(ToolDefinition tool) {
        return new HttpToolConfig(
                tool.tenantId(), tool.id(), HttpToolMethod.POST, tool.endpoint(), tool.inputSchema(),
                List.of(), Map.of(), Duration.ofSeconds(1)
        );
    }
}
