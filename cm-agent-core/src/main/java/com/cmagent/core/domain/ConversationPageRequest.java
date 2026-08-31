package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/** 会话列表使用的稳定复合游标。 */
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
