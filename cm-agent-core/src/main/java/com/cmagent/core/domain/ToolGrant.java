package com.cmagent.core.domain;

import java.util.UUID;

public record ToolGrant(
        UUID tenantId,
        UUID toolId,
        UUID agentId,
        // Null/blank roleCode means an agent-scoped grant. Non-empty values are metadata in the first slice.
        String roleCode,
        boolean granted
) {
}
