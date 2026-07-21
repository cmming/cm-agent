package com.cmagent.core.repository;

import com.cmagent.core.domain.McpToolPublication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface McpToolPublicationRepository {
    McpToolPublication save(McpToolPublication publication);

    Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId);

    Map<UUID, McpToolPublication> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    List<McpToolPublication> listEnabledByTenant(UUID tenantId);

    void delete(UUID tenantId, UUID toolId);
}
