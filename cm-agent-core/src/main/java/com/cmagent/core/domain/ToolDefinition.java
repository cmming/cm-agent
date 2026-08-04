package com.cmagent.core.domain;

import java.util.UUID;

/**
 * 描述租户内工具的名称、类型、风险级别、输入模式和启用状态。
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
