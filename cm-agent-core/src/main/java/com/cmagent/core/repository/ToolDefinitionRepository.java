package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ToolDefinitionRepository 的核心领域类型。
 */
public interface ToolDefinitionRepository {
    /**
     * 定义 save 操作。
     */
    ToolDefinition save(ToolDefinition tool);

    /**
     * 定义 findByTenantAndId 操作。
     */
    Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId);

    /**
     * 定义 listByTenant 操作。
     */
    List<ToolDefinition> listByTenant(UUID tenantId);

    /**
     * 定义 delete 操作。
     */
    void delete(UUID tenantId, UUID toolId);
}
