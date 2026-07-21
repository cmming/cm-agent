package com.cmagent.core.domain;

import java.util.Objects;

public record HttpParameterMapping(
        String sourcePointer,
        HttpParameterLocation location,
        String targetName,
        String targetPointer,
        boolean required,
        String defaultValueJson
) {

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

    public boolean hasDefaultValue() {
        return !defaultValueJson.isBlank();
    }
}
