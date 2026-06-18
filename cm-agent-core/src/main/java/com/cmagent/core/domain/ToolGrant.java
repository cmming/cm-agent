package com.cmagent.core.domain;

import java.util.UUID;

public record ToolGrant(
        UUID tenantId,
        UUID toolId,
        UUID agentId,
        String roleCode,
        boolean granted
) {
}
