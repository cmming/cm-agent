package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryToolRegistryTest {

    @Test
    void registerAndExecuteLocalTool() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        ToolDefinition definition = new ToolDefinition(
                toolId,
                tenantId,
                "echo",
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        registry.register(definition, request -> new ToolExecutionResult("收到：" + request.inputJson(), true));

        assertThat(registry.find(toolId)).contains(definition);
        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, "{\"text\":\"你好\"}"));
        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary())
                .isEqualTo("收到：{\"text\":\"你好\"}");
    }

    @Test
    void legacyRequestConstructorRemainsExecutable() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        ToolDefinition definition = new ToolDefinition(
                toolId, tenantId, "legacy-echo", "兼容旧请求", ToolType.LOCAL,
                "{\"type\":\"object\"}", ToolRiskLevel.LOW, true, "", "tester", "tester");
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(definition, request -> new ToolExecutionResult(
                request.hasRuntimeContext() ? "意外上下文" : request.inputJson(), true));

        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, "{}"));

        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary()).isEqualTo("{}");
    }

    @Test
    void executeMissingToolReturnsNotRegisteredMessage() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000102");

        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, "{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.outputSummary()).isEqualTo("工具未注册 " + toolId);
    }
}
