package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * RunToolCall 的核心领域类型。
 */
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
    /**
     * 构造 RunToolCall 实例并校验输入参数。
     */
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

    /**
     * 执行 normalizeText 操作。
     */
    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
