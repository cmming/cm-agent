package com.cmagent.core.domain;

import java.util.List;
import java.util.UUID;

/**
 * 描述租户内 Agent 的名称、系统提示词、模型绑定和启用状态。
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
     * 校验并规范化 Agent 定义的标识、模型绑定和基础配置。
      *
      * @param id 目标资源标识
      * @param tenantId 当前租户标识
      * @param name 目标对象名称
      * @param description 目标对象说明
      * @param systemPrompt Agent 系统提示词
      * @param modelProviderId Agent 绑定的模型配置标识
      * @param modelName 模型名称
      * @param temperature 模型采样温度
      * @param maxIterations Agent 最大推理迭代次数
      * @param enabled 是否启用目标能力
      * @param toolIds 工具标识集合
      * @param createdBy 创建主体标识
      * @param updatedBy 最后更新主体标识
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
