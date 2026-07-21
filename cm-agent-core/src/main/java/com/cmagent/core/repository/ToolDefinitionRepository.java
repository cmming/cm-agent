package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolDefinitionRepository {
    ToolDefinition save(ToolDefinition tool);
    Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId);
    List<ToolDefinition> listByTenant(UUID tenantId);
    void delete(UUID tenantId, UUID toolId);
}
