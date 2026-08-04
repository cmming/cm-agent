package com.cmagent.core.domain;

import java.util.Objects;

/**
 * 定义工具输入字段到路径、查询、请求头或请求体的映射规则。
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
     * 校验参数源指针与目标 HTTP 位置的映射约束。
      *
      * @param sourcePointer 参数映射源 JSON Pointer
      * @param location 参数写入的 HTTP 位置
      * @param targetName 目标参数名称
      * @param targetPointer 请求体目标 JSON Pointer
      * @param required 参数是否必填
      * @param defaultValueJson 默认值 JSON 文本
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
     * 判断当前参数映射是否声明了可用的默认值。
     *
     * @return 默认值 JSON 非空时返回 {@code true}
     */
    public boolean hasDefaultValue() {
        return !defaultValueJson.isBlank();
    }
}
