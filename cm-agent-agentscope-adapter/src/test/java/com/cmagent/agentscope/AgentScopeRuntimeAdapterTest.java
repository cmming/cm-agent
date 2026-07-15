package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeRuntimeAdapterTest {

    @Test
    void invokesReActAgentAndMapsFinalMessageText() {
        RecordingModel model = new RecordingModel("模型答案");
        AgentDefinition agent = agent();

        AgentRunResult result = new AgentScopeRuntimeAdapter(a -> model, Duration.ofSeconds(2))
                .run(request(agent, List.of()));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("模型答案");
        assertThat(model.options.getModelName()).isEqualTo("兼容模型");
        assertThat(model.options.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void mapsAgentDefinitionIntoRunSpec() {
        AgentScopeRunSpec spec = new AgentScopeRuntimeAdapter().toRunSpec(request(agent(), List.of()));

        assertThat(spec.systemPrompt()).isEqualTo("你是客服助手");
        assertThat(spec.modelName()).isEqualTo("兼容模型");
        assertThat(spec.temperature()).isEqualTo(0.7);
        assertThat(spec.maxIterations()).isEqualTo(4);
    }

    @Test
    void rejectsToolFromAnotherTenantWithoutExecutingIt() {
        ToolDefinition tool = new ToolDefinition(UUID.randomUUID(), UUID.randomUUID(), "查询", "查询工具", null, "{}", null, true, null, "", "");

        AgentRunResult result = new AgentScopeRuntimeAdapter().run(request(agent(), List.of(tool)));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.errorMessage()).contains("tenant");
    }

    @Test
    void mapsModelExceptionToControlledFailure() {
        AgentRunResult result = new AgentScopeRuntimeAdapter(a -> new RecordingModel(new IllegalStateException("供应商失败")), Duration.ofSeconds(2))
                .run(request(agent(), List.of()));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).contains("供应商失败");
    }

    @Test
    void mapsTimeoutToControlledFailure() {
        AgentRunResult result = new AgentScopeRuntimeAdapter(a -> new Model() {
            @Override public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.never();
            }
            @Override public String getModelName() { return "测试模型"; }
        }, Duration.ofMillis(10)).run(request(agent(), List.of()));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.errorMessage()).contains("Timeout");
    }

    private static AgentRunRequest request(AgentDefinition agent, List<ToolDefinition> tools) {
        return new AgentRunRequest(agent, agent.tenantId(), agent.id(), new PrincipalRef(agent.tenantId(), "admin", "管理员", Set.of("agent:run")), "查询客户状态", tools);
    }

    private static AgentDefinition agent() {
        return new AgentDefinition(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), "客服", "", "你是客服助手", UUID.randomUUID(), "兼容模型", 0.7, 4, true, List.of(), "admin", "admin");
    }

    private static final class RecordingModel implements Model {
        private final String text;
        private GenerateOptions options;
        private RecordingModel(String text) { this.text = text; }
        private RecordingModel(Throwable failure) { this.text = null; }
        @Override public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            this.options = options;
            if (text == null) return Flux.error(new IllegalStateException("供应商失败"));
            return Flux.just(new ChatResponse("id", List.<ContentBlock>of(TextBlock.builder().text(text).build()), null, null, "stop"));
        }
        @Override public String getModelName() { return "测试模型"; }
    }
}
