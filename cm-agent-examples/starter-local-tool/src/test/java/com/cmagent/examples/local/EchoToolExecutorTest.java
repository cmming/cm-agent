package com.cmagent.examples.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EchoToolExecutorTest {
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EchoToolExecutor executor = new EchoToolExecutor(objectMapper);

    @Test
    void shouldEchoNonBlankMessage() throws Exception {
        ToolExecutionResult result = executor.execute(request("""
                {"message":"你好，CM Agent"}
                """));

        assertThat(result.success()).isTrue();
        JsonNode output = objectMapper.readTree(result.outputSummary());
        assertThat(output.path("message").asText()).isEqualTo("你好，CM Agent");
    }

    @Test
    void shouldRejectInvalidJson() {
        ToolExecutionResult result = executor.execute(request("{"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("工具输入必须是合法 JSON 对象");
    }

    @Test
    void shouldRejectMissingMessage() {
        ToolExecutionResult result = executor.execute(request("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("message 必须是非空字符串");
    }

    @Test
    void shouldRejectNonStringMessage() {
        ToolExecutionResult result = executor.execute(request("""
                {"message":1}
                """));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("message 必须是非空字符串");
    }

    private ToolExecutionRequest request(String inputJson) {
        return new ToolExecutionRequest(TOOL_ID, inputJson);
    }
}
