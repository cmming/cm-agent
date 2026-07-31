package com.cmagent.server.runtime;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.server.runtime.http.HttpToolProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRuntimeReadinessTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void local只有注册身份完全匹配且启用时才就绪() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(registry, new HttpToolProperties());
        ToolDefinition stored = localTool(TOOL_ID, TENANT_ID, "echo", true);

        assertThat(readiness.isReady(stored, null)).isFalse();

        registry.register(stored, request -> ToolExecutionResult.succeeded("{}", null));

        assertThat(readiness.isReady(stored, null)).isTrue();
        assertThat(readiness.isReady(localTool(TOOL_ID, OTHER_TENANT_ID, "echo", true), null)).isFalse();
        assertThat(readiness.isReady(localTool(TOOL_ID, TENANT_ID, "renamed", true), null)).isFalse();
        assertThat(readiness.isReady(localTool(TOOL_ID, TENANT_ID, "echo", false), null)).isFalse();
    }

    @Test
    void http全局执行开关关闭时即使配置匹配也不就绪() {
        ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(new InMemoryToolRegistry(), new HttpToolProperties());
        ToolDefinition tool = httpTool(TOOL_ID, TENANT_ID, "https://api.example.test/orders", true);
        HttpToolConfig matching = httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders");

        assertThat(readiness.isReady(tool, matching)).isFalse();
        assertThat(readiness.isReady(tool, null)).isFalse();
        assertThat(readiness.isReady(tool, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/other"))).isFalse();
    }

    @Test
    void http全局执行开关开启且配置匹配时就绪() {
        HttpToolProperties properties = new HttpToolProperties();
        properties.setEnabled(true);
        ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(new InMemoryToolRegistry(), properties);
        ToolDefinition tool = httpTool(TOOL_ID, TENANT_ID, "https://api.example.test/orders", true);

        assertThat(readiness.isReady(tool, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders"))).isTrue();
    }

    private static ToolDefinition localTool(UUID id, UUID tenantId, String name, boolean enabled) {
        return new ToolDefinition(
                id, tenantId, name, "回显工具", ToolType.LOCAL, "{}", ToolRiskLevel.LOW, enabled,
                "", "admin", "admin"
        );
    }

    private static ToolDefinition httpTool(UUID id, UUID tenantId, String endpoint, boolean enabled) {
        return new ToolDefinition(
                id, tenantId, "orders", "订单工具", ToolType.HTTP, "{}", ToolRiskLevel.LOW, enabled,
                endpoint, "admin", "admin"
        );
    }

    private static HttpToolConfig httpConfig(UUID toolId, UUID tenantId, String urlTemplate) {
        return new HttpToolConfig(
                tenantId, toolId, HttpToolMethod.POST, urlTemplate, "{}", List.of(), Map.of(), Duration.ofSeconds(1)
        );
    }
}
