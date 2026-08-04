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
    /**
     * 验证方法名称所描述的业务行为。
     */
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
    /**
     * 验证方法名称所描述的业务行为。
     */
    void http全局执行开关关闭时即使配置匹配也不就绪() {
        ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(new InMemoryToolRegistry(), new HttpToolProperties());
        ToolDefinition tool = httpTool(TOOL_ID, TENANT_ID, "https://api.example.test/orders", true);
        HttpToolConfig matching = httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders");

        assertThat(readiness.isReady(tool, matching)).isFalse();
        assertThat(readiness.isReady(tool, null)).isFalse();
        assertThat(readiness.isReady(tool, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/other"))).isFalse();
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void http全局执行开关开启且配置匹配时就绪() {
        HttpToolProperties properties = new HttpToolProperties();
        properties.setEnabled(true);
        ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(new InMemoryToolRegistry(), properties);
        ToolDefinition tool = httpTool(TOOL_ID, TENANT_ID, "https://api.example.test/orders", true);

        assertThat(readiness.isReady(tool, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders"))).isTrue();
    }

    /**
     * 验证或支持 {@code localTool} 所描述的测试场景。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param name 测试对象名称
     * @param enabled 测试辅助方法使用的 enabled 参数
     */
    private static ToolDefinition localTool(UUID id, UUID tenantId, String name, boolean enabled) {
        return new ToolDefinition(
                id, tenantId, name, "回显工具", ToolType.LOCAL, "{}", ToolRiskLevel.LOW, enabled,
                "", "admin", "admin"
        );
    }

    /**
     * 验证或支持 {@code httpTool} 所描述的测试场景。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param endpoint 测试辅助方法使用的 endpoint 参数
     * @param enabled 测试辅助方法使用的 enabled 参数
     */
    private static ToolDefinition httpTool(UUID id, UUID tenantId, String endpoint, boolean enabled) {
        return new ToolDefinition(
                id, tenantId, "orders", "订单工具", ToolType.HTTP, "{}", ToolRiskLevel.LOW, enabled,
                endpoint, "admin", "admin"
        );
    }

    /**
     * 验证或支持 {@code httpConfig} 所描述的测试场景。
     *
     * @param toolId 测试工具标识
     * @param tenantId 测试租户标识
     * @param urlTemplate 测试辅助方法使用的 urlTemplate 参数
     */
    private static HttpToolConfig httpConfig(UUID toolId, UUID tenantId, String urlTemplate) {
        return new HttpToolConfig(
                tenantId, toolId, HttpToolMethod.POST, urlTemplate, "{}", List.of(), Map.of(), Duration.ofSeconds(1)
        );
    }
}
