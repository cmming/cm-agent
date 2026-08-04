package com.cmagent.server.runtime.http;

/**
 * 统一解析 JSON Pointer 中可能表示数组下标的 token，
 * 并限制可构造数组的最大下标以保证格式和范围安全。
 */
record HttpToolArrayIndex(int value) {
    static final int MAX_VALUE = 10_000;
    private static final String INVALID_MESSAGE = "JSON Pointer 数组索引无效或超过安全上限";
    /**
     * 解析数组索引文本并返回结构化结果。
     *
     * @param token 当前 JSON Pointer 路径片段。
     */
    static ParseResult parse(String token) {
        if (token == null || token.isEmpty()) {
            return ParseResult.nonNumeric();
        }
        int value = 0;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < '0' || character > '9') {
                return ParseResult.nonNumeric();
            }
            if (index == 1 && token.charAt(0) == '0') {
                return ParseResult.invalid();
            }
            int digit = character - '0';
            if (value > (MAX_VALUE - digit) / 10) {
                return ParseResult.invalid();
            }
            value = value * 10 + digit;
        }
        return ParseResult.valid(new HttpToolArrayIndex(value));
    }
    /**
     * 创建数组索引格式不合法异常。
     */
    static IllegalArgumentException invalidException() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }

    /**
     * 封装 {@code ParseResult} 在 HTTP 工具流程中使用的不可变数据。
     */
    record ParseResult(Status status, HttpToolArrayIndex index) {
        /**
         * 校验并构造 {@code ParseResult} 实例。
         *
     * @param status 数组路径片段的解析状态
     * @param index 数组路径片段解析得到的元素下标
         */
        ParseResult {
            if ((status == Status.VALID) != (index != null)) {
                throw new IllegalArgumentException("数组索引解析结果不一致");
            }
        }
        /**
         * 创建“数组索引不是数字”的解析结果。
         */
        static ParseResult nonNumeric() {
            return new ParseResult(Status.NON_NUMERIC, null);
        }
        /**
         * 创建通用无效解析结果。
         */
        static ParseResult invalid() {
            return new ParseResult(Status.INVALID, null);
        }
        /**
         * 创建包含有效数组下标的解析结果。
         *
         * @param index 目标数组下标。
         */
        static ParseResult valid(HttpToolArrayIndex index) {
            return new ParseResult(Status.VALID, index);
        }
        /**
         * 判断数组索引解析结果是否有效。
         */
        boolean isValid() {
            return status == Status.VALID;
        }
        /**
         * 判断数组索引解析结果是否无效。
         */
        boolean isInvalid() {
            return status == Status.INVALID;
        }
        /**
         * 校验当前位置必须由数组容器承载。
         */
        boolean requiresArrayContainer() {
            if (isInvalid()) {
                throw invalidException();
            }
            return isValid();
        }
        /**
         * 校验并返回有效数组下标。
         */
        int requireValue() {
            if (!isValid()) {
                throw invalidException();
            }
            return index.value();
        }
    }

    /**
     * 枚举 {@code Status} 支持的有限状态或类型。
     */
    enum Status {
        /** 路径片段不是数组下标。 */
        NON_NUMERIC,
        /** 路径片段看似下标但不符合合法格式。 */
        INVALID,
        /** 路径片段为可用的非负数组下标。 */
        VALID
    }
}
