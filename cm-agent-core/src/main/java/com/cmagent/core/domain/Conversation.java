package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 租户内固定归属于一个 Agent 的持久化会话。
 *
 * <p>会话创建后不能迁移至其他 Agent 或租户；{@code updatedAt} 是列表复合游标的一部分，必须不早于
 * {@code createdAt}。首条用户消息写入后，服务层可以将 {@link #DEFAULT_TITLE} 更新为输入摘要。</p>
 *
 * @param id 会话唯一标识
 * @param tenantId 会话归属租户，读取边界以此隔离
 * @param agentId 会话绑定的 Agent 标识，创建后不可变更
 * @param title 会话标题，长度 1 到 200；新会话使用 {@link #DEFAULT_TITLE}
 * @param createdBy 创建主体标识
 * @param createdAt 会话创建时间
 * @param updatedAt 最后活动时间，作为列表游标的第一排序键
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
