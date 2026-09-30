package com.cmagent.agentscope;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 使用本地 OpenAI 协议 stub 与真实 AgentScope ReActAgent，验证模型选择脚本后的完整适配链路。 */
class AgentScopeSkillRuntimeContractTest {
    @Test
    void 真实ReAct调用受治理脚本后将结果交给模型() throws Exception {
        var gateway = mock(SkillAccessGateway.class);
        when(gateway.executionEnabled()).thenReturn(true);
        when(gateway.execute(any(), eq("输入"))).thenReturn("沙箱结果");
        verifyRuntime(gateway, null);
        verify(gateway, times(1)).execute(any(), eq("输入"));
    }

    @Test
    void 真实ReAct不能把致命沙箱失败降级后继续模型调用() throws Exception {
        var gateway = mock(SkillAccessGateway.class);
        when(gateway.executionEnabled()).thenReturn(true);
        var failure = new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "技能沙箱不可用", "sandbox-contract-error", true);
        when(gateway.execute(any(), any())).thenThrow(failure);
        verifyRuntime(gateway, failure);
    }

    private static void verifyRuntime(SkillAccessGateway gateway, SkillAccessException expected) throws Exception {
        AgentRunRequest original = AgentScopeSkillExecutionBridgeTest.request();
        String skillId = new AgentScopeSkillRepository(original.skills()).getSkill("deploy-guide").getSkillId();
        var bodies = new CopyOnWriteArrayList<String>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = count.incrementAndGet() == 1 ? """
                    {"id":"local-script","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"script-call","type":"function","function":{"name":"run_skill_script","arguments":"{\\"skillId\\":\\"%s\\",\\"path\\":\\"scripts/main.py\\",\\"stdin\\":\\"输入\\"}"}}]},"finish_reason":"tool_calls"}]}
                    """.formatted(skillId) : """
                    {"id":"local-final","object":"chat.completion","created":2,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"脚本任务完成"},"finish_reason":"stop"}]}
                    """;
            byte[] data = ("data: " + response.strip() + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        try {
            var model = new ModelConfig(original.modelConfig().id(), original.tenantId(), original.modelConfig().providerType(),
                    "本地协议测试", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", true);
            var request = new AgentRunRequest(original.runId(), original.tenantId(), original.agent(), model,
                    original.principal(), "执行脚本", List.of(), null, original.skills());
            var runtime = AgentScopeRuntimeAdapter.create((tenant, id) -> new ModelCredential("invalid-local-contract-key"),
                    ignored -> { throw new AssertionError("不应调用业务工具"); },
                    new AgentScopeRuntimeOptions(Duration.ofSeconds(5), Duration.ofSeconds(3), 1), Clock.systemUTC(),
                    new io.agentscope.core.state.InMemoryAgentStateStore(), gateway);
            if (expected == null) {
                var result = runtime.run(request);
                assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
                assertThat(result.output()).isEqualTo("脚本任务完成");
                assertThat(count).hasValue(2);
                assertThat(bodies.getLast()).contains("沙箱结果");
            } else {
                assertThatThrownBy(() -> runtime.run(request)).isSameAs(expected);
                assertThat(count).hasValue(1);
            }
            assertThat(bodies.getFirst()).contains("run_skill_script").doesNotContain("run_skill_code", "print(1)");
        } finally {
            // HTTP stub 与端口仅服务当前测试，即使断言失败也释放；不访问公网模型或使用真实凭据。
            server.stop(0);
        }
    }
}
