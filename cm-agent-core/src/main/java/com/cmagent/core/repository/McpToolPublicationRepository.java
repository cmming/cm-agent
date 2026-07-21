package com.cmagent.core.repository;

import com.cmagent.core.domain.McpToolPublication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpToolPublicationRepository {
    McpToolPublication save(McpToolPublication publication);

    Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId);

    List<McpToolPublication> listEnabledByTenant(UUID tenantId);

    void delete(UUID tenantId, UUID toolId);
}
