package com.cmagent.core.domain;

/**
 * 枚举 HTTP Tool 参数定义支持的数据类型。
 */
public enum HttpParameterDataType {
    /** 任意文本，支持长度范围约束。 */
    STRING("string"),

    /** 整数。 */
    INTEGER("integer"),

    /** 含小数的数字。 */
    NUMBER("number"),

    /** 布尔值。 */
    BOOLEAN("boolean"),

    /** 嵌套对象，可通过 {@code parentId} 构造层级结构。 */
    OBJECT("object"),

    /** 数组，支持元素数量与唯一性约束。 */
    ARRAY("array");

    /**
     * 对应的 JSON Schema 类型名称，如 {@code string}、{@code object}。
     */
    private final String schemaType;

    HttpParameterDataType(String schemaType) {
        this.schemaType = schemaType;
    }

    /**
     * @return 对应的 JSON Schema 类型名称，如 {@code string}、{@code object}。
     */
    public String schemaType() {
        return schemaType;
    }

    /**
     * @return 是标量类型（非 OBJECT/ARRAY）时返回 {@code true}，这类类型不允许再有子参数。
     */
    public boolean scalar() {
        return this != OBJECT && this != ARRAY;
    }
}
