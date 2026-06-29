package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolGrant;
import java.util.List;
import java.util.UUID;

public interface ToolGrantRepository {
    ToolGrant save(ToolGrant grant);
    List<ToolGrant> listByTenant(UUID tenantId);
    List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId);
    List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId);
}
