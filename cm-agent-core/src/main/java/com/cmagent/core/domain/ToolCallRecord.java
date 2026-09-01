package com.cmagent.core.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * 描述运行时返回的单次工具调用名称、输入、输出、状态和耗时。
 *
 * @param toolId 被调用工具的标识
 * @param toolName 工具名称快照
 * @param inputSummary 已脱敏的输入摘要，只含字段名与受控说明而非原始参数值
 * @param outputSummary 已脱敏的输出摘要；失败时可能为空
 * @param status 调用终态（成功、失败或拒绝）
 * @param duration 调用耗时；尚无数据时为 {@code null}
 * @param authorized 本次工具调用是否通过授权复核
 * @param errorMessage 已脱敏的错误说明；无错误时为空字符串
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
