package com.cmagent.examples.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 校验输入并回显非空消息。
 */
public final class EchoToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;

    public EchoToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        try {
            JsonNode input = objectMapper.readTree(request.inputJson());
            JsonNode message = input == null || !input.isObject() ? null : input.get("message");
            if (message == null || !message.isTextual() || message.asText().isBlank()) {
                return ToolExecutionResult.failed("message 必须是非空字符串", null);
            }
            ObjectNode output = objectMapper.createObjectNode().put("message", message.asText());
            return ToolExecutionResult.succeeded(objectMapper.writeValueAsString(output), null);
        } catch (JsonProcessingException exception) {
            return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
        }
    }
}
