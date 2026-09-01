package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 租户内固定归属于一个 Agent 的持久化会话。
 *
 * <p>会话创建后不能迁移至其他 Agent 或租户；{@code updatedAt} 是列表复合游标的一部分，必须不早于
 * {@code createdAt}。首条用户消息写入后，服务层可以将 {@link #DEFAULT_TITLE} 更新为输入摘要。</p>
 */
public record Conversation(
        UUID id,
        UUID tenantId,
        UUID agentId,
        String title,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String DEFAULT_TITLE = "新会话";

    public Conversation {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title 长度必须为 1 到 200");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy 不能为空");
        }
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
    }
}
