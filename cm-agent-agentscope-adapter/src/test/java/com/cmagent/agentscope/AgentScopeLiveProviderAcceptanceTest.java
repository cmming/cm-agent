package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
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
