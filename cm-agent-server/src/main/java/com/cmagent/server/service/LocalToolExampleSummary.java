package com.cmagent.server.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

/** 内置 LOCAL 工具示例目录的稳定响应模型。 */
public record LocalToolExampleSummary(
        String key,
        UUID toolId,
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode sampleInput,
        boolean installed,
        boolean runtimeReady
) {
    /**
     * 校验并构造 {@code LocalToolExampleSummary} 实例。
     *
     * @param key 本地示例工具键
     * @param toolId 目标工具标识
     * @param name 目标对象名称。
     * @param description 目标对象说明。
     * @param inputSchema 工具输入 JSON Schema。
     * @param sampleInput 推荐的示例工具输入。
     * @param installed 示例工具是否已经安装。
     * @param runtimeReady 本地工具执行器是否已注册。
     */
    public LocalToolExampleSummary {
        key = Objects.requireNonNull(key, "key 不能为空");
        toolId = Objects.requireNonNull(toolId, "toolId 不能为空");
        name = Objects.requireNonNull(name, "name 不能为空");
        description = Objects.requireNonNull(description, "description 不能为空");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema 不能为空").deepCopy();
        sampleInput = Objects.requireNonNull(sampleInput, "sampleInput 不能为空").deepCopy();
    }

    @Override
    /**
     * 解析并返回示例工具的输入 Schema。
     */
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    /**
     * 解析并返回示例工具的推荐输入。
     */
    public JsonNode sampleInput() {
        return sampleInput.deepCopy();
    }
}
