package com.cmagent.core.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HttpToolConfig 的核心领域类型。
 */
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
    private static final Pattern SECRET_REFERENCE = Pattern.compile(
            "secret/[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*"
    );

    /**
     * 构造 HttpToolConfig 实例并校验输入参数。
     */
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
        if (secretHeaders.values().stream().anyMatch(value -> !SECRET_REFERENCE.matcher(value).matches())) {
            throw new IllegalArgumentException("secretHeaders 必须使用 secret/ 开头的引用");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        if (method == HttpToolMethod.GET && parameterMappings.stream()
                .anyMatch(mapping -> mapping.location() == HttpParameterLocation.BODY)) {
            throw new IllegalArgumentException("GET 工具不能配置 BODY 参数");
        }
    }
}
