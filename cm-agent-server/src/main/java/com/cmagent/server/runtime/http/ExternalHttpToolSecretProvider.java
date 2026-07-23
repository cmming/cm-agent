package com.cmagent.server.runtime.http;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExternalHttpToolSecretProvider implements HttpToolSecretProvider {
    private final HttpToolProperties properties;

    public ExternalHttpToolSecretProvider(HttpToolProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    @Override
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
