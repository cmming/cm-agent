package com.cmagent.api;

import java.time.Instant;
import java.util.Objects;

public record ApiErrorResponse(ApiErrorCode code, String message, Instant timestamp) {
    public ApiErrorResponse {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}
