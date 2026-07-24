package com.cmagent.server.runtime.http;

/**
 * 统一解析 JSON Pointer 中可能表示数组下标的 token，并限制可构造数组的最大下标。
 */

/**
 * JSON Pointer 数组下标的值对象，负责保证下标格式和范围有效。
 */
record HttpToolArrayIndex(int value) {
    static final int MAX_VALUE = 10_000;
    private static final String INVALID_MESSAGE = "JSON Pointer 数组索引无效或超过安全上限";
    /**
     * parse：读取并解析输入内容。
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
     * invalidException：处理该类内部的业务逻辑或辅助计算。
     */
    static IllegalArgumentException invalidException() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }

    /**
     * ParseResult：不可变数据载体，用于在本模块内传递结构化信息。
     */
    record ParseResult(Status status, HttpToolArrayIndex index) {
        ParseResult {
            if ((status == Status.VALID) != (index != null)) {
                throw new IllegalArgumentException("数组索引解析结果不一致");
            }
        }
        /**
         * nonNumeric：处理该类内部的业务逻辑或辅助计算。
         */
        static ParseResult nonNumeric() {
            return new ParseResult(Status.NON_NUMERIC, null);
        }
        /**
         * invalid：处理该类内部的业务逻辑或辅助计算。
         */
        static ParseResult invalid() {
            return new ParseResult(Status.INVALID, null);
        }
        /**
         * valid：处理该类内部的业务逻辑或辅助计算。
         */
        static ParseResult valid(HttpToolArrayIndex index) {
            return new ParseResult(Status.VALID, index);
        }
        /**
         * isValid：判断当前条件是否成立。
         */
        boolean isValid() {
            return status == Status.VALID;
        }
        /**
         * isInvalid：判断当前条件是否成立。
         */
        boolean isInvalid() {
            return status == Status.INVALID;
        }
        /**
         * requiresArrayContainer：校验输入、状态或前置条件。
         */
        boolean requiresArrayContainer() {
            if (isInvalid()) {
                throw invalidException();
            }
            return isValid();
        }
        /**
         * requireValue：校验输入、状态或前置条件。
         */
        int requireValue() {
            if (!isValid()) {
                throw invalidException();
            }
            return index.value();
        }
    }

    /**
     * Status：枚举本模块使用的有限状态或类型。
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
