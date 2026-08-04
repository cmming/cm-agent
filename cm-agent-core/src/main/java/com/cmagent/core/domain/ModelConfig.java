package com.cmagent.core.domain;

import java.util.UUID;

/**
 * 描述租户内模型提供商、模型名称、端点和凭据引用配置。
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
