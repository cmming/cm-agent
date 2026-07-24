package com.cmagent.server.runtime.http;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从外部映射读取 HTTP 工具 secret，未配置时明确拒绝执行。
 */
public final class ExternalHttpToolSecretProvider implements HttpToolSecretProvider {
    private final HttpToolProperties properties;

    public ExternalHttpToolSecretProvider(HttpToolProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    @Override
    /**
     * 按租户解析 HTTP 工具引用的 secret。
     *
     * @param tenantId 当前租户标识
     * @param secretRef 配置中的 secret 引用
     * @return 找到且属于当前租户的 secret，否则返回空
     */
    public Optional<String> resolve(UUID tenantId, String secretRef) {
        if (tenantId == null || secretRef == null || secretRef.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getSecrets().get(compositeKey(tenantId, secretRef)))
                .filter(value -> !value.isBlank());
    }

    static String compositeKey(UUID tenantId, String secretRef) {
        return Objects.requireNonNull(tenantId, "tenantId 不能为空") + "|" +
                Objects.requireNonNull(secretRef, "secretRef 不能为空");
    }

    @Override
    public String toString() {
        return "ExternalHttpToolSecretProvider{secrets=<已脱敏>}";
    }
}
