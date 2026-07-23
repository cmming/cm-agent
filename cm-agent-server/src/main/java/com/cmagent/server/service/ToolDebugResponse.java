package com.cmagent.server.service;

public record ToolDebugResponse(
        boolean success,
        Integer statusCode,
        String output,
        String errorMessage,
        long durationMillis
) {
}
