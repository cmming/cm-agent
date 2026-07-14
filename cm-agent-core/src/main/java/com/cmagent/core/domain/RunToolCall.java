package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RunToolCall(
        UUID id,
        UUID tenantId,
        UUID runId,
        UUID toolId,
        String toolName,
        String inputSummary,
        String outputSummary,
        RunStatus status,
        boolean authorized,
        Long durationMillis,
        String errorMessage,
        Instant createdAt
) {
    public RunToolCall {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (durationMillis != null && durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能小于 0");
        }
        inputSummary = normalizeText(inputSummary);
        outputSummary = normalizeText(outputSummary);
        errorMessage = normalizeText(errorMessage);
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
