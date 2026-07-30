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
    public LocalToolExampleSummary {
        key = Objects.requireNonNull(key, "key 不能为空");
        toolId = Objects.requireNonNull(toolId, "toolId 不能为空");
        name = Objects.requireNonNull(name, "name 不能为空");
        description = Objects.requireNonNull(description, "description 不能为空");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema 不能为空").deepCopy();
        sampleInput = Objects.requireNonNull(sampleInput, "sampleInput 不能为空").deepCopy();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonNode sampleInput() {
        return sampleInput.deepCopy();
    }
}
