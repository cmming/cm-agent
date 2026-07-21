package com.cmagent.core.tool;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void completeRuntimeContextRemainsExecutable() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID agentId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000104");
        PrincipalRef principal = new PrincipalRef(
                tenantId, "principal", "测试主体", Set.of("agent:run"));
        ToolDefinition definition = new ToolDefinition(
                toolId, tenantId, "context-echo", "回显上下文请求", ToolType.LOCAL,
                "{\"type\":\"object\"}", ToolRiskLevel.LOW, true, "", "tester", "tester");
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(definition, request -> new ToolExecutionResult(
                request.hasRuntimeContext() ? request.inputJson() : "缺少运行上下文", true));

        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(
                tenantId, agentId, principal, runId, "tool-call-1", toolId, "{}"));

        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary()).isEqualTo("{}");
    }

    @Test
    void rejectsPartialRuntimeContext() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000105");

        assertThatThrownBy(() -> new ToolExecutionRequest(
                tenantId, null, null, null, null, toolId, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具执行上下文必须全部提供或全部省略");
    }

    @Test
    void rejectsBlankToolCallIdInCompleteRuntimeContext() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PrincipalRef principal = new PrincipalRef(
                tenantId, "principal", "测试主体", Set.of("agent:run"));

        assertThatThrownBy(() -> new ToolExecutionRequest(
                tenantId,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                principal,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "  ",
                UUID.fromString("00000000-0000-0000-0000-000000000106"),
                "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallId 不能为空");
    }

    @Test
    void rejectsCrossTenantPrincipalInCompleteRuntimeContext() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PrincipalRef principal = new PrincipalRef(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "principal", "测试主体", Set.of("agent:run"));

        assertThatThrownBy(() -> new ToolExecutionRequest(
                tenantId,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                principal,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "tool-call-1",
                UUID.fromString("00000000-0000-0000-0000-000000000107"),
                "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("调用主体不属于当前租户");
    }

    @Test
    void executeMissingToolReturnsNotRegisteredMessage() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000102");

        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, "{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.outputSummary()).isEqualTo("工具未注册 " + toolId);
    }

    @Test
    void agent请求要求完整运行上下文且结果工厂保留状态信息() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000108");
        PrincipalRef principal = new PrincipalRef(
                tenantId, "principal", "测试主体", Set.of("agent:run"));

        ToolExecutionRequest request = new ToolExecutionRequest(
                tenantId,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                principal,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "tool-call-1",
                toolId,
                "{}",
                ToolInvocationSource.AGENT);

        ToolExecutionResult result = ToolExecutionResult.succeeded("工具输出", 200);
        ToolExecutionResult failure = ToolExecutionResult.failed("工具调用失败", 502);

        assertThat(request.hasRuntimeContext()).isTrue();
        assertThat(request.source()).isEqualTo(ToolInvocationSource.AGENT);
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.errorMessage()).isEmpty();
        assertThat(failure.success()).isFalse();
        assertThat(failure.statusCode()).isEqualTo(502);
        assertThat(failure.errorMessage()).isEqualTo("工具调用失败");
        assertThat(ToolType.valueOf("HTTP")).isEqualTo(ToolType.HTTP);
    }

    @Test
    void MCP请求禁止绑定伪造运行上下文() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PrincipalRef principal = new PrincipalRef(
                tenantId, "principal", "测试主体", Set.of("tool:mcp:invoke"));

        assertThatThrownBy(() -> new ToolExecutionRequest(
                tenantId,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                principal,
                null,
                "tool-call-1",
                UUID.fromString("00000000-0000-0000-0000-000000000109"),
                "{}",
                ToolInvocationSource.MCP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MCP 调用不能绑定 agentId 或 runId");
    }
}
