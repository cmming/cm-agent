package com.cmagent.core.repository;

import com.cmagent.core.domain.AgentDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository {
    AgentDefinition save(AgentDefinition agent);
    Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId);
    List<AgentDefinition> listByTenant(UUID tenantId);
    AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId);
}
