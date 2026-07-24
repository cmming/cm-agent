package com.cmagent.core.repository;

import com.cmagent.core.domain.McpToolPublication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * McpToolPublicationRepository 的核心领域类型。
 */
public interface McpToolPublicationRepository {
    /**
     * 定义 save 操作。
     */
    McpToolPublication save(McpToolPublication publication);

    /**
     * 定义 findByTenantAndToolId 操作。
     */
    Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId);

    /**
     * 定义 findByTenantAndToolIds 操作。
     */
    Map<UUID, McpToolPublication> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    /**
     * 定义 listEnabledByTenant 操作。
     */
    List<McpToolPublication> listEnabledByTenant(UUID tenantId);

    /**
     * 定义 delete 操作。
     */
    void delete(UUID tenantId, UUID toolId);
}
