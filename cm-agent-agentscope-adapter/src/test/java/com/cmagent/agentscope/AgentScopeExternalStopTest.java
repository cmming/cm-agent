package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.ModelCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式授权后才运行的外部模型停止验收；普通测试不联网、不产生费用。
 *
 * <p>仅从环境读取凭据，在内存创建隔离的定义，不修改 Server 模型配置或 Agent 绑定。
 * 首轮收到正文立即停止，第二轮确认同一运行时可以新建请求；不挂载任何工具。</p>
 */
@EnabledIfEnvironmentVariable(named = "CM_AGENT_EXTERNAL_STOP_TEST", matches = "true")
class AgentScopeExternalStopTest {
    @Test
    void 真实模型正文开始后停止且允许新轮正常完成() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assertThat(key != null && !key.isBlank()).as("需要已授权的环境凭据").isTrue();
        var runtime = AgentScopeRuntimeAdapter.create((tenant, model) -> new ModelCredential(key),
                request -> { throw new AssertionError("外部模型验收不得进入工具网关"); },
                new AgentScopeRuntimeOptions(Duration.ofSeconds(60), Duration.ofSeconds(5), 1), Clock.systemUTC());
        var first = request("从一开始逐行写出数字及中文解释，至少写一百行，不要省略。");
        var deltas = new AtomicInteger();
        var stopped = runtime.runStructured(first, delta -> {
            if (!delta.delta().isBlank() && deltas.incrementAndGet() == 1) first.cancellation().request();
        }, event -> { });
        assertThat(deltas.get()).as("必须观察真实正文，不能把鉴权失败当作停止成功").isPositive();
        assertThat(stopped.run().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(stopped.run().toolCalls()).isEmpty();
        var next = runtime.run(request("只回答：你好。"));
        assertThat(next.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(next.output().isBlank()).isFalse();
        assertThat(next.toolCalls()).isEmpty();
    }

    private static AgentRunRequest request(String input) {
        UUID tenant = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        String name = "qwen3.8-27b";
        var model = new ModelConfig(modelId, tenant, ModelProviderType.OPENAI_COMPATIBLE,
                "已授权停止验收", "https://dashscope.aliyuncs.com/compatible-mode/v1", name, true);
        var agent = new AgentDefinition(UUID.randomUUID(), tenant, "隔离停止验收", "", "简洁回答",
                modelId, name, 0.2, 1, true, List.of(), "tester", "tester");
        return new AgentRunRequest(UUID.randomUUID(), tenant, agent, model,
                new PrincipalRef(tenant, "tester", "测试主体", Set.of("agent:run")), input, List.of());
    }
}
