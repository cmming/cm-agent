package com.cmagent.core.domain;

/**
 * 枚举 HTTP Tool 参数定义支持的数据类型。
 */
public enum HttpParameterDataType {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    OBJECT("object"),
    ARRAY("array");

    private final String schemaType;

    HttpParameterDataType(String schemaType) {
        this.schemaType = schemaType;
    }

    /**
     * @return 对应的 JSON Schema 类型名称。
     */
    public String schemaType() {
        return schemaType;
    }

    /**
     * @return 是否为不包含子结构的标量类型。
     */
    public boolean scalar() {
        return this != OBJECT && this != ARRAY;
    }
}
