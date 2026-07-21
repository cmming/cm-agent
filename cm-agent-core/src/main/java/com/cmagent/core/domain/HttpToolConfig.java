package com.cmagent.core.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HttpToolConfig(
        UUID tenantId,
        UUID toolId,
        HttpToolMethod method,
        String urlTemplate,
        String inputSchema,
        List<HttpParameterMapping> parameterMappings,
        Map<String, String> secretHeaders,
        Duration timeout
) {

    public HttpToolConfig {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(method, "method 不能为空");
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new IllegalArgumentException("urlTemplate 不能为空");
        }
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("inputSchema 不能为空");
        }
        parameterMappings = List.copyOf(parameterMappings == null ? List.of() : parameterMappings);
        secretHeaders = Map.copyOf(secretHeaders == null ? Map.of() : secretHeaders);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        if (method == HttpToolMethod.GET && parameterMappings.stream()
                .anyMatch(mapping -> mapping.location() == HttpParameterLocation.BODY)) {
            throw new IllegalArgumentException("GET 工具不能配置 BODY 参数");
        }
    }
}
