package com.cmagent.core.domain;

import java.util.UUID;

/**
 * 描述租户内工具的名称、类型、风险级别、输入模式和启用状态。
 *
 * @param id 工具唯一标识
 * @param tenantId 工具归属租户，授权与读取边界以此隔离
 * @param name 租户内唯一的工具名称，删除后墓碑不释放该名称
 * @param description 面向模型与控制台的工具说明
 * @param type 工具类型，决定执行链路；编辑时锁定不可变更
 * @param inputSchema 描述输入结构的 JSON Schema 文本
 * @param riskLevel 风险等级；HIGH 工具调试需二次确认
 * @param enabled 是否启用；禁用工具在授权阶段被拒绝
 * @param endpoint 目标端点元数据，仅 HTTP/MCP/A2A 类型有意义；不会被自动执行
 * @param createdBy 创建主体标识
 * @param updatedBy 最后更新主体标识
 */
public record ToolDefinition(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        ToolType type,
        String inputSchema,
        ToolRiskLevel riskLevel,
        boolean enabled,
        String endpoint,
        String createdBy,
        String updatedBy
) {
}
