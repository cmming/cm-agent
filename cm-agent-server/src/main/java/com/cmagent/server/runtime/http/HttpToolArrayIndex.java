package com.cmagent.server.runtime.http;

/**
 * 统一解析 JSON Pointer 中可能表示数组下标的 token，并限制可构造数组的最大下标。
 */
record HttpToolArrayIndex(int value) {
    static final int MAX_VALUE = 10_000;
    private static final String INVALID_MESSAGE = "JSON Pointer 数组索引无效或超过安全上限";

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

    static IllegalArgumentException invalidException() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }

    record ParseResult(Status status, HttpToolArrayIndex index) {
        ParseResult {
            if ((status == Status.VALID) != (index != null)) {
                throw new IllegalArgumentException("数组索引解析结果不一致");
            }
        }

        static ParseResult nonNumeric() {
            return new ParseResult(Status.NON_NUMERIC, null);
        }

        static ParseResult invalid() {
            return new ParseResult(Status.INVALID, null);
        }

        static ParseResult valid(HttpToolArrayIndex index) {
            return new ParseResult(Status.VALID, index);
        }

        boolean isValid() {
            return status == Status.VALID;
        }

        boolean isInvalid() {
            return status == Status.INVALID;
        }

        boolean requiresArrayContainer() {
            if (isInvalid()) {
                throw invalidException();
            }
            return isValid();
        }

        int requireValue() {
            if (!isValid()) {
                throw invalidException();
            }
            return index.value();
        }
    }

    enum Status {
        NON_NUMERIC,
        INVALID,
        VALID
    }
}
