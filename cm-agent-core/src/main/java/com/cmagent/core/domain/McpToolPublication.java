package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

public record McpToolPublication(
        UUID tenantId,
        UUID toolId,
        boolean enabled,
        String publishedBy
) {

    public McpToolPublication {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        if (publishedBy == null || publishedBy.isBlank()) {
            throw new IllegalArgumentException("publishedBy 不能为空");
        }
        publishedBy = publishedBy.trim();
    }
}
