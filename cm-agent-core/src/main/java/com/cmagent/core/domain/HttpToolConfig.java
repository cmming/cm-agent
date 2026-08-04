package com.cmagent.core.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 描述动态 HTTP 工具的地址、方法、参数映射、认证和响应限制。
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
     * 校验并规范化动态 HTTP 工具的地址、映射、认证与响应限制。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
      * @param method HTTP 请求方法
      * @param urlTemplate HTTP 工具 URL 模板
      * @param inputSchema 工具输入 JSON Schema
      * @param parameterMappings HTTP 参数映射集合
      * @param secretHeaders 请求头名称到 Secret 引用的映射
      * @param timeout HTTP 调用超时
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
