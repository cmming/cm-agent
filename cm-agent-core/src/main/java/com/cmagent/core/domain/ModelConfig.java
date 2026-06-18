package com.cmagent.core.domain;

import java.util.UUID;

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
