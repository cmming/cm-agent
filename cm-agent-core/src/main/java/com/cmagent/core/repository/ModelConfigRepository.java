package com.cmagent.core.repository;

import com.cmagent.core.domain.ModelConfig;

import java.util.Optional;
import java.util.UUID;

public interface ModelConfigRepository {

    Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);
}
