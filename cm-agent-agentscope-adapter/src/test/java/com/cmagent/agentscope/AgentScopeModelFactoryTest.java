package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.runtime.ModelCredential;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentScopeModelFactoryTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

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

    private static AgentDefinition agent() {
        return new AgentDefinition(AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手",
                MODEL_ID, "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
    }
}
