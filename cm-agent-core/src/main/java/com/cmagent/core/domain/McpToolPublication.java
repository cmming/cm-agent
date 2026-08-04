package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 记录租户内工具是否通过 MCP 对外发布及其更新时间。
 */
public record McpToolPublication(
        UUID tenantId,
        UUID toolId,
        boolean enabled,
        String publishedBy
) {

    /**
     * 校验并规范化工具的 MCP 发布状态与更新时间。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
      * @param enabled 是否启用目标能力
      * @param publishedBy 发布主体标识
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
