package com.cmagent.core.repository;

import com.cmagent.core.domain.ModelConfig;

import java.util.Optional;
import java.util.UUID;

/**
 * ModelConfigRepository 的核心领域类型。
 */
public interface ModelConfigRepository {

    /**
     * 定义 findByTenantAndId 操作。
     */
    Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);
}
