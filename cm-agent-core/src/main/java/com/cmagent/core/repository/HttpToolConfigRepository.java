package com.cmagent.core.repository;

import com.cmagent.core.domain.HttpToolConfig;

import java.util.Optional;
import java.util.UUID;

public interface HttpToolConfigRepository {
    HttpToolConfig save(HttpToolConfig config);

    Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId);

    void delete(UUID tenantId, UUID toolId);
}
