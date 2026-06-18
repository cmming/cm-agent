package com.cmagent.core.domain;

import java.util.UUID;

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
