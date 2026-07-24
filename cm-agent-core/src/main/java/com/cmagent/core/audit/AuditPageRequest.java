package com.cmagent.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated keyset page request for audit events ordered by {@code createdAt DESC, id DESC}.
 */
public record AuditPageRequest(int limit, Instant beforeCreatedAt, UUID beforeId) {
    /**
     * 构造 AuditPageRequest 实例并校验输入参数。
     */
    public AuditPageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("beforeCreatedAt 与 beforeId 必须同时为空或同时非空");
        }
    }
}
