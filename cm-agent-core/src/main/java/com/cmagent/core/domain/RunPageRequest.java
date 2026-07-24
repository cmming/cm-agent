package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated keyset page request for runs ordered by {@code startedAt DESC, id DESC}.
 */
public record RunPageRequest(int limit, Instant beforeStartedAt, UUID beforeId) {
    /**
     * 构造 RunPageRequest 实例并校验输入参数。
     */
    public RunPageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((beforeStartedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("beforeStartedAt 与 beforeId 必须同时为空或同时非空");
        }
    }
}
