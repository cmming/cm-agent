package com.cmagent.api;

import java.time.Instant;
import java.util.Objects;

/**
 * API 请求失败时返回的统一错误结构。
 *
 * @param code 可供客户端稳定识别的错误码
 * @param message 已脱敏、可直接展示的错误说明
 * @param timestamp 服务端生成响应的时间
 */
public record ApiErrorResponse(ApiErrorCode code, String message, Instant timestamp) {
    /**
     * 校验错误码、说明和时间，确保错误响应始终完整可用。
      *
      * @param code 稳定的 API 错误码
      * @param message 待记录或返回的消息文本
      * @param timestamp 错误响应时间
     */
    public ApiErrorResponse {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}
