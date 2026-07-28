package com.cmagent.examples.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AddToolExecutorTest {
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AddToolExecutor executor = new AddToolExecutor(objectMapper);

    @Test
    void shouldAddIntegers() throws Exception {
        ToolExecutionResult result = executor.execute(request("""
                {"left":2,"right":3}
                """));

        assertThat(result.success()).isTrue();
        assertThat(sum(result)).isEqualByComparingTo("5");
    }

    @Test
    void shouldAddDecimalsExactly() throws Exception {
        ToolExecutionResult result = executor.execute(request("""
                {"left":0.1,"right":0.2}
                """));

        assertThat(result.success()).isTrue();
        assertThat(sum(result)).isEqualByComparingTo("0.3");
    }

    @Test
    void shouldAddNegativeNumbers() throws Exception {
        ToolExecutionResult result = executor.execute(request("""
                {"left":-5.5,"right":2}
                """));

        assertThat(result.success()).isTrue();
        assertThat(sum(result)).isEqualByComparingTo("-3.5");
    }

    @Test
    void shouldRejectInvalidJson() {
        ToolExecutionResult result = executor.execute(request("{"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("工具输入必须是合法 JSON 对象");
    }

    @Test
    void shouldRejectMissingOperand() {
        ToolExecutionResult result = executor.execute(request("""
                {"left":1}
                """));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("left 和 right 必须是数字");
    }

    @Test
    void shouldRejectNonNumericOperand() {
        ToolExecutionResult result = executor.execute(request("""
                {"left":"1","right":2}
                """));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("left 和 right 必须是数字");
    }

    private ToolExecutionRequest request(String inputJson) {
        return new ToolExecutionRequest(TOOL_ID, inputJson);
    }

    private java.math.BigDecimal sum(ToolExecutionResult result) throws Exception {
        JsonNode output = objectMapper.readTree(result.outputSummary());
        return output.path("sum").decimalValue();
    }
}
