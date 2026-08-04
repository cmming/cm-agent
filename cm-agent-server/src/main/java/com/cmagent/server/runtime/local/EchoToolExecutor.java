package com.cmagent.server.runtime.local;

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

    /**
     * 校验并构造 {@code EchoToolExecutor} 实例。
     *
     * @param objectMapper 用于 JSON 解析和序列化的组件
     */
    public EchoToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    /**
     * 执行本地示例工具并返回 JSON 结果。
     *
     * @param request 当前业务请求
     */
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        try {
            JsonNode input = objectMapper.readTree(request.inputJson());
            JsonNode message = input == null || !input.isObject() ? null : input.get("message");
            if (message == null || !message.isTextual() || message.textValue().isBlank()) {
                return ToolExecutionResult.failed("message 必须是非空字符串", null);
            }
            ObjectNode output = objectMapper.createObjectNode().put("message", message.textValue());
            return ToolExecutionResult.succeeded(objectMapper.writeValueAsString(output), null);
        } catch (JsonProcessingException exception) {
            return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
        }
    }
}
