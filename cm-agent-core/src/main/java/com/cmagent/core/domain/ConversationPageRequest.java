package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话列表使用的稳定复合游标。
 *
 * <p>{@code beforeUpdatedAt} 与 {@code beforeId} 必须同时存在或同时缺失；两者共同消除同一更新时间下的
 * 分页歧义，不能把时间戳单独作为游标。</p>
 */
public record ConversationPageRequest(int limit, Instant beforeUpdatedAt, UUID beforeId) {
    public ConversationPageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((beforeUpdatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("会话游标字段必须同时提供");
        }
    }
}
