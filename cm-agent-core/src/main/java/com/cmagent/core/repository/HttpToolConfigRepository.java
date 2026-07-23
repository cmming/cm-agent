package com.cmagent.core.repository;

import com.cmagent.core.domain.HttpToolConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface HttpToolConfigRepository {
    HttpToolConfig save(HttpToolConfig config);

    Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId);

    Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds);

    void delete(UUID tenantId, UUID toolId);
}
