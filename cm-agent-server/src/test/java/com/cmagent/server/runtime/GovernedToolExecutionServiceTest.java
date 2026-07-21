package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.runtime.http.DynamicHttpToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernedToolExecutionServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private HttpToolConfigRepository configs;
    @Mock
    private DynamicHttpToolExecutor http;
    @Mock
    private ToolRegistry registry;

    private GovernedToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new GovernedToolExecutionService(configs, http, registry);
    }

    @Test
    void httpExecutionReloadsMatchingTenantConfigurationForEveryInvocation() {
        ToolDefinition tool = tool(ToolType.HTTP, TENANT_ID, TOOL_ID, "http-tool", true, "https://example.invalid/items");
        HttpToolConfig config = config(TENANT_ID, TOOL_ID, "https://example.invalid/items");
        when(configs.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(config));
        when(http.execute(eq(tool), eq(config), any())).thenReturn(
                ToolExecutionResult.succeeded("已执行", 200)
        );

        ToolExecutionResult first = service.execute(tool, request(ToolInvocationSource.DEBUG));
        ToolExecutionResult second = service.execute(tool, request(ToolInvocationSource.MCP));

        assertThat(first).isEqualTo(ToolExecutionResult.succeeded("已执行", 200));
        assertThat(second).isEqualTo(ToolExecutionResult.succeeded("已执行", 200));
        verify(configs, org.mockito.Mockito.times(2)).findByTenantAndToolId(TENANT_ID, TOOL_ID);
        verify(http).execute(tool, config, request(ToolInvocationSource.DEBUG));
        verify(http).execute(tool, config, request(ToolInvocationSource.MCP));
    }

    @Test
    void httpExecutionRejectsConfigurationWhoseUrlTemplateDiffersFromDefinitionEndpoint() {
        ToolDefinition tool = tool(ToolType.HTTP, TENANT_ID, TOOL_ID, "http-tool", true, "https://example.invalid/items");
        when(configs.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(
                Optional.of(config(TENANT_ID, TOOL_ID, "https://other.invalid/items"))
        );

        ToolExecutionResult result = service.execute(tool, request(ToolInvocationSource.DEBUG));

        assertUnavailable(result);
        verifyNoInteractions(http, registry);
    }

    @Test
    void localExecutionRequiresSameTenantIdAndNameInRegistryBeforeExecution() {
        ToolDefinition tool = tool(ToolType.LOCAL, TENANT_ID, TOOL_ID, "echo", true, "");
        when(registry.find(TOOL_ID)).thenReturn(Optional.of(tool));
        ToolExecutionRequest request = request(ToolInvocationSource.MCP);
        when(registry.execute(request)).thenReturn(ToolExecutionResult.succeeded("已执行", null));

        ToolExecutionResult result = service.execute(tool, request);

        assertThat(result).isEqualTo(ToolExecutionResult.succeeded("已执行", null));
        verify(registry).execute(request);
        verifyNoInteractions(configs, http);
    }

    @ParameterizedTest
    @MethodSource("unavailableTools")
    void disabledCrossTenantAndUnsupportedToolsReturnOneFixedUnavailableResult(ToolDefinition tool) {
        ToolExecutionResult result = service.execute(tool, request(ToolInvocationSource.DEBUG));

        assertUnavailable(result);
        verifyNoInteractions(configs, http, registry);
    }

    @Test
    void localExecutionRejectsMissingOrInconsistentRegistryDefinitions() {
        ToolDefinition tool = tool(ToolType.LOCAL, TENANT_ID, TOOL_ID, "echo", true, "");
        when(registry.find(TOOL_ID)).thenReturn(
                Optional.empty(),
                Optional.of(tool(ToolType.LOCAL, OTHER_TENANT_ID, TOOL_ID, "echo", true, "")),
                Optional.of(tool(ToolType.LOCAL, TENANT_ID, OTHER_TOOL_ID, "echo", true, "")),
                Optional.of(tool(ToolType.LOCAL, TENANT_ID, TOOL_ID, "other", true, ""))
        );

        for (int ignored = 0; ignored < 4; ignored++) {
            assertUnavailable(service.execute(tool, request(ToolInvocationSource.DEBUG)));
        }

        verify(registry, never()).execute(any());
        verifyNoInteractions(configs, http);
    }

    private static Stream<ToolDefinition> unavailableTools() {
        return Stream.of(
                tool(ToolType.HTTP, TENANT_ID, TOOL_ID, "http-tool", false, "https://example.invalid/items"),
                tool(ToolType.HTTP, OTHER_TENANT_ID, TOOL_ID, "http-tool", true, "https://example.invalid/items"),
                tool(ToolType.LOCAL, TENANT_ID, OTHER_TOOL_ID, "other-tool", true, ""),
                tool(ToolType.MCP, TENANT_ID, TOOL_ID, "mcp-tool", true, ""),
                tool(ToolType.A2A, TENANT_ID, TOOL_ID, "a2a-tool", true, "")
        );
    }

    private static ToolDefinition tool(
            ToolType type, UUID tenantId, UUID toolId, String name, boolean enabled, String endpoint
    ) {
        return new ToolDefinition(
                toolId, tenantId, name, "工具", type, "{}", ToolRiskLevel.LOW,
                enabled, endpoint, "principal", "principal"
        );
    }

    private static HttpToolConfig config(UUID tenantId, UUID toolId, String urlTemplate) {
        return new HttpToolConfig(
                tenantId, toolId, HttpToolMethod.GET, urlTemplate, "{}", List.of(), java.util.Map.of(), Duration.ofSeconds(1)
        );
    }

    private static ToolExecutionRequest request(ToolInvocationSource source) {
        return new ToolExecutionRequest(
                TENANT_ID,
                null,
                new PrincipalRef(TENANT_ID, "principal", "管理员", Set.of("tool:invoke")),
                null,
                "call-1",
                TOOL_ID,
                "{\"text\":\"hello\"}",
                source
        );
    }

    private static void assertUnavailable(ToolExecutionResult result) {
        assertThat(result).isEqualTo(ToolExecutionResult.failed("工具不可用", null));
    }
}
