package com.cmagent.core.domain;

import java.util.UUID;

/**
 * 描述某个 Agent 对工具的租户内授权关系。
 */
public record ToolGrant(
        UUID tenantId,
        UUID toolId,
        UUID agentId,
        // Null/blank roleCode means an agent-scoped grant. Non-empty values are metadata in the first slice.
        String roleCode,
        boolean granted
) {
}
