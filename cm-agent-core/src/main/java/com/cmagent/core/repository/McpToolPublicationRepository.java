package com.cmagent.core.repository;

import com.cmagent.core.domain.McpToolPublication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义工具 MCP 发布状态按租户保存、查询和删除的持久化契约。
 */
public interface McpToolPublicationRepository {
    /**
     * 在当前租户边界内保存领域记录。
      *
      * @param publication MCP 工具发布记录
     */
    McpToolPublication save(McpToolPublication publication);

    /**
     * 按租户和工具标识查询唯一配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
     */
    Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId);

    /**
     * 按租户批量查询指定工具的配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolIds 工具标识集合
     */
    Map<UUID, McpToolPublication> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    /**
     * 按租户列出当前启用的 MCP 工具发布记录。
      *
      * @param tenantId 当前租户标识
     */
    List<McpToolPublication> listEnabledByTenant(UUID tenantId);

    /**
     * 删除当前租户内目标工具对应的配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
     */
    void delete(UUID tenantId, UUID toolId);
}
