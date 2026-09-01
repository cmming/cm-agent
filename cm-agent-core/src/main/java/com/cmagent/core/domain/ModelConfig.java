package com.cmagent.core.domain;

import java.net.URI;
import java.util.UUID;

/**
 * 描述租户内模型提供商、模型名称和端点配置。
 *
 * @param id 模型配置唯一标识，Agent 绑定时引用该值
 * @param tenantId 配置归属租户
 * @param providerType 模型提供商类型，决定 adapter 使用的模型实现
 * @param displayName 控制台展示名称
 * @param baseUrl 模型端点地址，须为无用户信息与片段的 HTTP(S) 绝对地址
 * @param modelName 默认模型名称
 * @param enabled 是否启用；停用的配置不接受新运行
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

    /**
     * 校验并规范化模型配置。模型凭据不属于该领域对象，由仓储密文与运行时提供者单独管理。
     *
     * <p>baseUrl 要求为 HTTP(S) 绝对地址且不携带用户信息（防凭据注入）与片段（无路由意义）。</p>
     */
    public ModelConfig {
        displayName = requiredText(displayName, "displayName", 160);
        baseUrl = requiredText(baseUrl, "baseUrl", 500);
        modelName = requiredText(modelName, "modelName", 160);

        URI endpoint = URI.create(baseUrl);
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl 必须是无用户信息和片段的 HTTP(S) 绝对地址");
        }
    }

    private static String requiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
