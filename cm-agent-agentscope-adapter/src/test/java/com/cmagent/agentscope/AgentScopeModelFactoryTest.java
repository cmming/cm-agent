package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.runtime.ModelCredential;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.OpenAIClient;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentScopeModelFactoryTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");

    private final AgentScopeModelFactory factory = new AgentScopeModelFactory();

    @Test
    void createsOpenAiCompatibleModel() {
        Model model = factory.create(openAiConfig(), agent(), new ModelCredential("test-key"));

        assertThat(model).isInstanceOf(OpenAIChatModel.class);
        assertThat(((OpenAIChatModel) model).getModelName()).isEqualTo("agent-model");
        assertThat(generateOptions(model, "configuredOptions").getTemperature()).isEqualTo(0.2);
    }

    @Test
    void createsDashScopeNativeModel() {
        Model model = factory.create(dashScopeConfig(), agent(), new ModelCredential("test-key"));

        assertThat(model).isInstanceOf(DashScopeChatModel.class);
        assertThat(((DashScopeChatModel) model).getModelName()).isEqualTo("agent-model");
        assertThat(generateOptions(model, "defaultOptions").getTemperature()).isEqualTo(0.2);
    }

    @Test
    void fallsBackToConfiguredModelNameWhenAgentModelNameIsBlank() {
        AgentDefinition agent = new AgentDefinition(
                AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", MODEL_ID,
                "  ", 0.2, 5, true, List.of(), "tester", "tester");

        Model model = factory.create(openAiConfig(), agent, new ModelCredential("test-key"));

        assertThat(model.getModelName()).isEqualTo("default-model");
    }

    @Test
    void 为OpenCode运行注入固定会话头且不影响其他兼容网关() {
        ModelConfig openCode = new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "OpenCode Go", "https://opencode.ai/zen/go/v1", "default-model", true);
        OpenAIChatModel model = (OpenAIChatModel) factory.create(openCode, agent(), new ModelCredential("test-key"), RUN_ID);
        OpenAIClient client = (OpenAIClient) ReflectionTestUtils.getField(model, "client");

        assertThat(client.getTransport()).isInstanceOf(ProviderSessionHeaderHttpTransport.class);
        assertThat(((OpenAIClient) ReflectionTestUtils.getField(
                factory.create(openAiConfig(), agent(), new ModelCredential("test-key")), "client"))
                .getTransport()).isNotInstanceOf(ProviderSessionHeaderHttpTransport.class);
    }

    @Test
    void 会话传输覆盖请求头并不修改其他字段() {
        CapturingTransport delegate = new CapturingTransport();
        ProviderSessionHeaderHttpTransport transport = new ProviderSessionHeaderHttpTransport(delegate, RUN_ID.toString());
        HttpRequest request = HttpRequest.builder().url("https://opencode.ai/zen/go/v1/chat/completions")
                .method("POST").headers(Map.of("Content-Type", "application/json"))
                .body("{}").build();

        transport.execute(request);

        assertThat(delegate.request.getUrl()).isEqualTo(request.getUrl());
        assertThat(delegate.request.getMethod()).isEqualTo("POST");
        assertThat(delegate.request.getBody()).isEqualTo("{}");
        assertThat(delegate.request.getHeaders()).containsEntry(
                ProviderSessionHeaderHttpTransport.OPENCODE_SESSION_HEADER, RUN_ID.toString());
    }

    @Test
    void validatesRuntimeOptions() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AgentScopeRuntimeOptions(Duration.ZERO, Duration.ofSeconds(1), 1))
                .withMessage("模型超时必须大于 0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AgentScopeRuntimeOptions(Duration.ofSeconds(1), Duration.ZERO, 1))
                .withMessage("工具超时必须大于 0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AgentScopeRuntimeOptions(Duration.ofSeconds(1), Duration.ofSeconds(1), 6))
                .withMessage("模型最大尝试次数必须在 1 到 5 之间");
    }

    private static GenerateOptions generateOptions(Model model, String fieldName) {
        return (GenerateOptions) ReflectionTestUtils.getField(model, fieldName);
    }

    private static ModelConfig openAiConfig() {
        return new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "OpenAI兼容", "https://example.invalid/v1", "default-model", true);
    }

    private static ModelConfig dashScopeConfig() {
        return new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
                "DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", true);
    }

    /**
     * 构造测试 Agent 定义。
     */
    private static AgentDefinition agent() {
        return new AgentDefinition(AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手",
                MODEL_ID, "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
    }

    /** 测试 Provider 传输包装器时记录最终请求，不发起网络调用。 */
    private static final class CapturingTransport implements HttpTransport {
        private HttpRequest request;

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.request = request;
            return HttpResponse.builder().statusCode(200).body("{}").build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            this.request = request;
            return Flux.empty();
        }

        @Override
        public void close() {
        }
    }
}
