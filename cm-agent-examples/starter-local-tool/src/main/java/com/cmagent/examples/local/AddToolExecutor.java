package com.cmagent.examples.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 对输入中的两个数字执行精确加法。
 */
public final class AddToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;

    public AddToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        try {
            JsonNode input = objectMapper.readTree(request.inputJson());
            JsonNode left = input == null || !input.isObject() ? null : input.get("left");
            JsonNode right = input == null || !input.isObject() ? null : input.get("right");
            if (left == null || right == null || !left.isNumber() || !right.isNumber()) {
                return ToolExecutionResult.failed("left 和 right 必须是数字", null);
            }
            BigDecimal sum = left.decimalValue().add(right.decimalValue());
            ObjectNode output = objectMapper.createObjectNode().put("sum", sum);
            return ToolExecutionResult.succeeded(objectMapper.writeValueAsString(output), null);
        } catch (JsonProcessingException exception) {
            return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
        }
    }
}
