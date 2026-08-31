package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 租户内固定归属于一个 Agent 的持久化会话。 */
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
