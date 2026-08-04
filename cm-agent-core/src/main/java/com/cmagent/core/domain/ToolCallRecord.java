package com.cmagent.core.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * 描述运行时返回的单次工具调用名称、输入、输出、状态和耗时。
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
