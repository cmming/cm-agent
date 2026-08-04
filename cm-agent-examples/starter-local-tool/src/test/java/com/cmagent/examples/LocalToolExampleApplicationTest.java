package com.cmagent.examples;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.main.web-application-type=none")
class LocalToolExampleApplicationTest {
    private static final UUID ECHO_TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ADD_TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired
    private ToolRegistry registry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    /**
     * 验证或支持 {@code shouldRegisterEchoAndAddTools} 所描述的测试场景。
     */
    void shouldRegisterEchoAndAddTools() throws Exception {
        ToolDefinition echo = registry.find(ECHO_TOOL_ID).orElseThrow();
        ToolDefinition add = registry.find(ADD_TOOL_ID).orElseThrow();

        assertThat(echo.name()).isEqualTo("echo");
        assertThat(add.name()).isEqualTo("add");

        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(
                ADD_TOOL_ID, "{\"left\":2,\"right\":3}"
        ));
        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.outputSummary()).path("sum").decimalValue())
                .isEqualByComparingTo("5");
    }
}
