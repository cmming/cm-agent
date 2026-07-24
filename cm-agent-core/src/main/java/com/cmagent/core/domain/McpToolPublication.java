package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * McpToolPublication 的核心领域类型。
 */
public record McpToolPublication(
        UUID tenantId,
        UUID toolId,
        boolean enabled,
        String publishedBy
) {

    /**
     * 构造 McpToolPublication 实例并校验输入参数。
     */
    public McpToolPublication {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        if (publishedBy == null || publishedBy.isBlank()) {
            throw new IllegalArgumentException("publishedBy 不能为空");
        }
        publishedBy = publishedBy.trim();
    }
}
