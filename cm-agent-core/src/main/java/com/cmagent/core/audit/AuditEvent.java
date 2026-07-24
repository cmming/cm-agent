package com.cmagent.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditEvent 的核心领域类型。
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
