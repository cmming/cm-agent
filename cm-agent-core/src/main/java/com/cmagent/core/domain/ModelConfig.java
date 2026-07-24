package com.cmagent.core.domain;

import java.util.UUID;

/**
 * ModelConfig 的核心领域类型。
 */
public record ModelConfig(
        UUID id,
        UUID tenantId,
        ModelProviderType providerType,
        String displayName,
        String baseUrl,
        String modelName,
        boolean enabled
) {
}
