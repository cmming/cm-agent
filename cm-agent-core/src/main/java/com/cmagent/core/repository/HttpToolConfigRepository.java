package com.cmagent.core.repository;

import com.cmagent.core.domain.HttpToolConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HttpToolConfigRepository 的核心领域类型。
 */
public interface HttpToolConfigRepository {
    /**
     * 定义 save 操作。
     */
    HttpToolConfig save(HttpToolConfig config);

    /**
     * 定义 findByTenantAndToolId 操作。
     */
    Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId);

    /**
     * 定义 findByTenantAndToolIds 操作。
     */
    Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    /**
     * 定义 delete 操作。
     */
    void delete(UUID tenantId, UUID toolId);
}
