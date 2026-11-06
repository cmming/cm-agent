package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两策略使用真实DashScope原生及其OpenAI兼容接口的准入合同。
 *
 * <p>只有显式设置CM_AGENT_TEST_LIVE_PROVIDER=true才联网，凭据由现有环境注入并只存内存。
 * 工具是无外部副作用的合成网关，证明模型确实调用CM入口，不代表所有Provider或真实业务工具。
 * 断言只输出固定原因，避免失败时将模型正文或凭据复制到Surefire报告。</p>
 */
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_LIVE_PROVIDER", matches = "true")
class AgentScopeLiveProviderAcceptanceTest {
    static Stream<Arguments> modesAndProviders() {
        return Arrays.stream(AgentScopeExecutionMode.values()).flatMap(mode ->
                Arrays.stream(ModelProviderType.values()).map(provider -> Arguments.of(mode, provider)));
    }

    static Stream<Arguments> authorizationModes() {
        return Arrays.stream(AgentScopeExecutionMode.values()).flatMap(execution ->
                Arrays.stream(OperationAuthorizationMode.values()).map(mode -> Arguments.of(execution, mode)));
    }

    static Stream<Arguments> projectAuthorizationModes() {
        return Arrays.stream(AgentScopeExecutionMode.values()).flatMap(execution ->
                Arrays.stream(OperationAuthorizationMode.values()).map(mode -> Arguments.of(execution, mode)));
    }

    @ParameterizedTest @MethodSource("authorizationModes")
    void 真实模型的本机工具遵循三种确认方式并可恢复(
            AgentScopeExecutionMode execution, OperationAuthorizationMode mode) {
        var original = request(ModelProviderType.OPENAI_COMPATIBLE, List.of(),
                "必须调用一次 local_client_command 工具取得本轮回执，然后原样输出回执，不要猜测回执。");
        UUID tenant = original.tenantId(), conversation = UUID.randomUUID();
        UUID toolId = UUID.nameUUIDFromBytes(("cm-agent-local-client-tool:" + tenant + ":COMMAND")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var tool = new ToolDefinition(toolId, tenant, "local_client_command", "获取本轮随机回执，只调用一次，无参数。",
                ToolType.LOCAL, "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                ToolRiskLevel.HIGH, true, "cm-agent-local://command", "system", "system");
        Instant now = Instant.now();
        var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, original.principal().principalId(),
                original.agentId(), conversation, original.runId(), UUID.randomUUID(), mode,
                Set.of(OperationCategory.COMMAND), 1, now, now.plusSeconds(300));
        var request = new AgentRunRequest(original.runId(), tenant, original.agent(), original.modelConfig(),
                original.principal(), original.input(), List.of(tool), conversation, List.of(), Map.of(),
                new RunCancellation(), null, null, grant);
        String receipt = "验收回执" + UUID.randomUUID();
        var calls = new AtomicInteger();
        var runtime = runtime(execution, call -> { calls.incrementAndGet(); return ToolInvocationResult.succeeded(receipt); });
        var result = runtime.runStructured(request, ignored -> {}, ignored -> {});
        if (mode == OperationAuthorizationMode.FULL_ACCESS) {
            assertEquals(RunStatus.SUCCEEDED, result.run().status(), "自动方式未成功");
        } else {
            assertEquals(RunStatus.WAITING_APPROVAL, result.run().status(), "询问方式未暂停");
            assertEquals(0, calls.get(), "审批前不得执行工具");
            var pending = result.pendingApproval().items();
            assertEquals(1, pending.size(), "待审批调用数量不正确");
            var item = pending.getFirst();
            var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(
                    item.toolCallId(), item.toolId(), item.inputHash(), ToolApprovalDecision.APPROVE)));
            result = runtime.resumeStructured(request, decisions, ignored -> {}, ignored -> {});
            assertEquals(RunStatus.SUCCEEDED, result.run().status(), "审批恢复未成功");
        }
        assertEquals(1, calls.get(), "工具调用次数不正确");
        // 模型可能改写回执；本合同关注暂停、恢复和网关调用次数，原样输出由独立合同验证。
        assertFalse(result.run().output().isBlank(), "模型未返回最终回答");
    }

    @ParameterizedTest @MethodSource("projectAuthorizationModes")
    void 真实模型的项目工具遵循三种确认方式并可恢复(
            AgentScopeExecutionMode execution, OperationAuthorizationMode mode) {
        var original = request(ModelProviderType.OPENAI_COMPATIBLE, List.of(),
                "必须调用一次 project_run_command 工具取得本轮回执，然后输出最终回答，不要猜测回执。");
        UUID tenant = original.tenantId(), project = UUID.randomUUID(), conversation = UUID.randomUUID();
        UUID toolId = UUID.nameUUIDFromBytes(("cm-agent/local-project/v1/" + tenant + "/RUN_COMMAND")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var tool = new ToolDefinition(toolId, tenant, "project_run_command",
                "获取本轮项目验收随机回执，只调用一次，无参数。", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                ToolRiskLevel.HIGH, true, "local-project:RUN_COMMAND", "system", "system");
        Instant now = Instant.now();
        var binding = new LocalProjectBinding(tenant, original.principal().principalId(), project,
                original.agentId(), conversation, original.runId(), UUID.randomUUID(), "0".repeat(64),
                "test-node", 1, now);
        var projectContext = new ProjectRuntimeContext(binding, Set.of(toolId), mode);
        var request = new AgentRunRequest(original.runId(), tenant, original.agent(), original.modelConfig(),
                original.principal(), original.input(), List.of(tool), conversation, List.of(), Map.of(),
                new RunCancellation(), null, projectContext, null);
        String receipt = "项目验收回执" + UUID.randomUUID();
        var calls = new AtomicInteger();
        var runtime = runtime(execution, call -> { calls.incrementAndGet(); return ToolInvocationResult.succeeded(receipt); });

        var result = runtime.runStructured(request, ignored -> {}, ignored -> {});
        if (mode == OperationAuthorizationMode.FULL_ACCESS) {
            assertEquals(RunStatus.SUCCEEDED, result.run().status(), "项目自动方式未成功");
        } else {
            assertEquals(RunStatus.WAITING_APPROVAL, result.run().status(), "项目询问方式未暂停");
            assertEquals(0, calls.get(), "项目审批前不得执行工具");
            var item = result.pendingApproval().items().getFirst();
            var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(
                    item.toolCallId(), item.toolId(), item.inputHash(), ToolApprovalDecision.APPROVE)));
            result = runtime.resumeStructured(request, decisions, ignored -> {}, ignored -> {});
            assertEquals(RunStatus.SUCCEEDED, result.run().status(), "项目审批恢复未成功");
        }
        assertEquals(1, calls.get(), "项目工具调用次数不正确");
        assertFalse(result.run().output().isBlank(), "模型未返回最终回答");
    }

    @ParameterizedTest @MethodSource("modesAndProviders")
    void 真实中文流式响应通过两策略与两协议(AgentScopeExecutionMode mode, ModelProviderType provider) {
        var request = request(provider, List.of(), "只回答中文“验收成功”，不要调用工具。");
        var runtime = runtime(mode, call -> { throw new AssertionError("无工具任务不得调用网关"); });
        var result = runtime.run(request);
        assertTrue(result.status() == RunStatus.SUCCEEDED, "真实Provider未成功；请按受控诊断检查连通性");
        assertTrue(result.output().contains("验收成功"), "真实中文回答未达到约定");
    }

    @ParameterizedTest @MethodSource("modesAndProviders")
    void 真实模型经治理网关取得本轮随机回执(AgentScopeExecutionMode mode, ModelProviderType provider) {
        var original = request(provider, List.of(), "调用一次acceptance_receipt工具取得回执，然后原样输出回执。");
        var tool = new ToolDefinition(UUID.randomUUID(), original.tenantId(), "acceptance_receipt",
                "获取本轮验收随机回执，只调用一次，无参数。", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                ToolRiskLevel.LOW, true, null, "tester", "tester");
        String receipt = "验收回执" + UUID.randomUUID();
        var calls = new AtomicInteger();
        var runtime = runtime(mode, call -> {
            assertTrue(call.tenantId().equals(original.tenantId()), "工具上下文租户不一致");
            calls.incrementAndGet();
            return ToolInvocationResult.succeeded(receipt);
        });
        var request = new AgentRunRequest(original.runId(), original.tenantId(), original.agent(),
                original.modelConfig(), original.principal(), original.input(), List.of(tool));
        var result = runtime.run(request);
        assertTrue(result.status() == RunStatus.SUCCEEDED, "真实工具任务未成功");
        assertTrue(calls.get() == 1, "治理网关执行次数不是一次");
        assertTrue(result.output().contains(receipt), "模型没有使用网关实际回执");
    }

    /** 每次创建独立Store和策略，框架临时目录与订阅由本次执行关闭，不跨验收共享。 */
    private static AgentScopeRuntimeAdapter runtime(AgentScopeExecutionMode mode, ToolInvocationGateway gateway) {
        String credential = System.getenv("DASHSCOPE_API_KEY");
        assertTrue(credential != null && !credential.isBlank(), "缺少验证环境凭据");
        return AgentScopeRuntimeAdapter.create((tenant, model) -> new ModelCredential(credential), gateway,
                new AgentScopeRuntimeOptions(Duration.ofSeconds(60), Duration.ofSeconds(5), 1),
                Clock.systemUTC(), new InMemoryAgentStateStore(),
                (read, loader) -> { throw new AssertionError("本合同不应读取技能"); }, mode);
    }

    private static AgentRunRequest request(ModelProviderType provider, List<ToolDefinition> tools, String input) {
        UUID tenant = UUID.randomUUID(), modelId = UUID.randomUUID();
        // 已核对2.0.2 DashScopeHttpClient字节码：原生客户端会自行追加/api/v1/services路径，
        // 因此原生baseUrl只给域名；兼容协议仍保留/compatible-mode/v1。
        String baseUrl = provider == ModelProviderType.OPENAI_COMPATIBLE
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : "https://dashscope.aliyuncs.com";
        String modelName = Optional.ofNullable(System.getenv("CM_AGENT_TEST_LIVE_MODEL")).orElse("qwen-plus");
        var model = new ModelConfig(modelId, tenant, provider, "真实协议验收", baseUrl, modelName, true);
        var agent = new AgentDefinition(UUID.randomUUID(), tenant, "真实验收", "",
                "你是中文验收助手，严格完成用户给出的简单任务。", modelId, modelName, 0.0, 4,
                true, tools.stream().map(ToolDefinition::id).toList(), "tester", "tester");
        var principal = new PrincipalRef(tenant, "tester", "验收主体", Set.of("agent:run"));
        return new AgentRunRequest(UUID.randomUUID(), tenant, agent, model, principal, input, tools);
    }
}
