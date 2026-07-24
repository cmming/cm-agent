package com.cmagent.server.service;

/**
 * 工具调试结果的脱敏响应模型。
 */
public record ToolDebugResponse(
        boolean success,
        Integer statusCode,
        String output,
        String errorMessage,
        long durationMillis
) {
}
