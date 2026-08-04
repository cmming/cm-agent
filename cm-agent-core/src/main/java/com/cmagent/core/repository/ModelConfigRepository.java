package com.cmagent.core.repository;

import com.cmagent.core.domain.ModelConfig;

import java.util.Optional;
import java.util.UUID;

/**
 * 定义模型配置按租户保存、查询和删除的持久化契约。
 */
public interface ModelConfigRepository {

    /**
     * 按租户和资源标识查询唯一记录。
      *
      * @param tenantId 当前租户标识
      * @param modelConfigId 模型配置标识
     */
    Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);
}
