package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * ToolGrantRepository 的核心领域类型。
 */
public interface ToolGrantRepository {
    /**
     * 定义 save 操作。
     */
    ToolGrant save(ToolGrant grant);

    /**
     * 定义 listByTenant 操作。
     */
    List<ToolGrant> listByTenant(UUID tenantId);

    /**
     * 定义 listByTenantAndAgent 操作。
     */
    List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId);

    /**
     * 定义 listByTenantAgentAndTool 操作。
     */
    List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId);
}
