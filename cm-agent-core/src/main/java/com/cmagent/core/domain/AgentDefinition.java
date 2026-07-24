package com.cmagent.core.domain;

import java.util.List;
import java.util.UUID;

/**
 * AgentDefinition 的核心领域类型。
 */
public record AgentDefinition(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        String systemPrompt,
        UUID modelProviderId,
        String modelName,
        double temperature,
        int maxIterations,
        boolean enabled,
        List<UUID> toolIds,
        String createdBy,
        String updatedBy
) {

    /**
     * 构造 AgentDefinition 实例并校验输入参数。
     */
    public AgentDefinition {
        toolIds = List.copyOf(toolIds);
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
        }
        if (maxIterations < 1 || maxIterations > 30) {
            throw new IllegalArgumentException("maxIterations 必须在 1 到 30 之间");
        }
    }
}
