package com.cmagent.core.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * ToolCallRecord 的核心领域类型。
 */
public record ToolCallRecord(
        UUID toolId,
        String toolName,
        String inputSummary,
        String outputSummary,
        RunStatus status,
        Duration duration,
        boolean authorized,
        String errorMessage
) {
}
