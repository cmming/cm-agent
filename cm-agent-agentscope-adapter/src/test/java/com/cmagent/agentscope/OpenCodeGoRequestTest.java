package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.runtime.ModelCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.OpenAIClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeGoRequestTest {

    @ParameterizedTest
    @CsvSource({
            "https://opencode.ai/zen/go/v1, true, true",
            "https://opencode.ai/zen/go/v1/, true, true",
            "https://OPENCODE.AI/zen/go/v1, true, true",
            "https://opencode.ai/zen/go/v1, false, true",
            "https://example.invalid/v1, true, false",
            "https://opencode.ai.example.invalid/zen/go/v1, true, false",
            "https://opencode.ai/zen/v1, true, false",
            "https://opencode.ai/zen/go/v10, true, false"
    })
    void 实际模型请求仅为Go省略消息名称并保留工具与原始消息(
            String baseUrl, boolean withRunId, boolean omitNames) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ModelConfig config = new ModelConfig(modelId, tenantId, ModelProviderType.OPENAI_COMPATIBLE,
                "协议测试", baseUrl, "test-model", true);
        AgentDefinition agent = new AgentDefinition(UUID.randomUUID(), tenantId, "测试助手", "", "测试提示",
                modelId, "test-model", 0.2, 5, true, List.of(), "tester", "tester");
        AgentScopeModelFactory factory = new AgentScopeModelFactory();
        Model model = withRunId
                ? factory.create(config, agent, new ModelCredential("invalid-local-test-key"), runId)
                : factory.create(config, agent, new ModelCredential("invalid-local-test-key"));
        OpenAIClient client = (OpenAIClient) ReflectionTestUtils.getField(model, "client");
        CapturingTransport transport = new CapturingTransport();
        // 只替换最终网络出口，保留真实工厂、格式化器、JSON 编码器和会话头包装器；测试不访问公网。
        boolean hasSessionHeader = client.getTransport() instanceof ProviderSessionHeaderHttpTransport;
        if (hasSessionHeader) {
            ReflectionTestUtils.setField(client.getTransport(), "delegate", transport);
        } else {
            ReflectionTestUtils.setField(client, "transport", transport);
        }
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).name("system").textContent("测试提示").build(),
                Msg.builder().role(MsgRole.USER).name("user").textContent("正文中的 name 必须保留").build(),
                Msg.builder().role(MsgRole.ASSISTANT).name("assistant").content(
                        TextBlock.builder().text("查询工具").build(),
                        ToolUseBlock.builder().id("call-1").name("lookup")
                                .input(Map.of("name", "张三")).build()).build(),
                Msg.builder().role(MsgRole.TOOL).name("tool").content(ToolResultBlock.builder()
                        .id("call-1").name("lookup")
                        .output(TextBlock.builder().text("查询结果").build()).build()).build());
        ToolSchema tool = ToolSchema.builder().name("lookup").description("查询测试数据")
                .parameters(Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))))
                .build();

        model.stream(messages, List.of(tool), null).collectList().block(Duration.ofSeconds(5));

        JsonNode body = new ObjectMapper().readTree(transport.request.getBody());
        assertThat(body.path("messages")).hasSize(4);
        for (JsonNode message : body.path("messages")) {
            if (omitNames) {
                assertThat(message.has("name")).isFalse();
            }
        }
        if (!omitNames) {
            assertThat(body.at("/messages/0/name").asText()).isEqualTo("system");
            assertThat(body.at("/messages/1/name").asText()).isEqualTo("user");
            assertThat(body.at("/messages/2/name").asText()).isEqualTo("assistant");
        }
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/1/content").asText()).isEqualTo("正文中的 name 必须保留");
        assertThat(body.at("/messages/2/tool_calls/0/function/name").asText()).isEqualTo("lookup");
        assertThat(new ObjectMapper().readTree(body.at("/messages/2/tool_calls/0/function/arguments").asText())
                .path("name").asText()).isEqualTo("张三");
        assertThat(body.at("/messages/3/tool_call_id").asText()).isEqualTo("call-1");
        assertThat(body.at("/messages/3/content").asText()).isEqualTo("查询结果");
        assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("lookup");
        assertThat(body.at("/tools/0/function/parameters/properties/name/type").asText()).isEqualTo("string");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(messages).extracting(Msg::getName).containsExactly("system", "user", "assistant", "tool");
        if (hasSessionHeader) {
            assertThat(transport.request.getHeaders()).containsEntry("x-opencode-session", runId.toString());
        } else {
            assertThat(transport.request.getHeaders()).doesNotContainKey("x-opencode-session");
        }
    }

    /** 记录框架生成的实际 HTTP 报文，以空事件流结束本地协议验证，不保存或使用真实凭据。 */
    private static final class CapturingTransport implements HttpTransport {
        private HttpRequest request;

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new AssertionError("流式模型不应调用同步传输");
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
