package com.cmagent.core.domain;

import java.util.Objects;

/**
 * HttpParameterMapping 的核心领域类型。
 */
public record HttpParameterMapping(
        String sourcePointer,
        HttpParameterLocation location,
        String targetName,
        String targetPointer,
        boolean required,
        String defaultValueJson
) {

    /**
     * 构造 HttpParameterMapping 实例并校验输入参数。
     */
    public HttpParameterMapping {
        sourcePointer = Objects.requireNonNull(sourcePointer, "sourcePointer 不能为空");
        location = Objects.requireNonNull(location, "location 不能为空");
        targetName = targetName == null ? "" : targetName.trim();
        targetPointer = targetPointer == null ? "" : targetPointer.trim();
        defaultValueJson = defaultValueJson == null ? "" : defaultValueJson;
        if (!sourcePointer.isEmpty() && !sourcePointer.startsWith("/")) {
            throw new IllegalArgumentException("sourcePointer 必须是 JSON Pointer");
        }
        if (location == HttpParameterLocation.BODY) {
            if (!targetName.isEmpty() || !targetPointer.startsWith("/")) {
                throw new IllegalArgumentException("BODY 参数必须只提供 targetPointer");
            }
        } else if (targetName.isBlank() || !targetPointer.isEmpty()) {
            throw new IllegalArgumentException("非 BODY 参数必须只提供 targetName");
        }
    }

    /**
     * 执行 hasDefaultValue 操作。
     */
    public boolean hasDefaultValue() {
        return !defaultValueJson.isBlank();
    }
}
