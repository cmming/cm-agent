package com.cmagent.core.repository;

import com.cmagent.core.domain.HttpToolConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义动态 HTTP 工具配置按租户保存、查询和删除的持久化契约。
 */
public interface HttpToolConfigRepository {
    /**
     * 在当前租户边界内保存领域记录。
      *
      * @param config 当前模型或工具配置
     */
    HttpToolConfig save(HttpToolConfig config);

    /**
     * 按租户和工具标识查询唯一配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
     */
    Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId);

    /**
     * 按租户批量查询指定工具的配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolIds 工具标识集合
     */
    Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    /**
     * 删除当前租户内目标工具对应的配置或发布记录。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
     */
    void delete(UUID tenantId, UUID toolId);
}
