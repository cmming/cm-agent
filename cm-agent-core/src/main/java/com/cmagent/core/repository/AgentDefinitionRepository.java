package com.cmagent.core.repository;

import com.cmagent.core.domain.AgentDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AgentDefinitionRepository 的核心领域类型。
 */
public interface AgentDefinitionRepository {
    /**
     * 定义 save 操作。
     */
    AgentDefinition save(AgentDefinition agent);

    /**
     * 定义 findByTenantAndId 操作。
     */
    Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId);

    /**
     * 定义 listByTenant 操作。
     */
    List<AgentDefinition> listByTenant(UUID tenantId);

    /**
     * 定义 addToolToAgent 操作。
     */
    AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId);
}
