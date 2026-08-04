package com.cmagent.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * 记录租户内安全或业务操作的主体、动作、资源、结果和发生时间。
 */
public record AuditEvent(
        UUID id,
        UUID tenantId,
        String principalId,
        String eventType,
        String resourceType,
        String resourceId,
        String status,
        String message,
        Instant createdAt
) {
}
