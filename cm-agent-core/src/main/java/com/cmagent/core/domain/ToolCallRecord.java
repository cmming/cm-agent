package com.cmagent.core.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * 描述运行时返回的单次工具调用名称、输入、输出、状态和耗时。
 *
 * @param toolId 被调用工具的标识
 * @param toolCallId 本次运行内稳定的工具调用标识，用于关联实时事件和会话内容块
 * @param toolName 工具名称快照
 * @param inputSummary 已限长、尚待服务端脱敏的可展示输入 JSON；失败前也会保留本次实际调用入参
 * @param outputSummary 已限长、尚待服务端脱敏的可展示返回值；失败时可能为空
 * @param status 调用终态（成功、失败或拒绝）
 * @param duration 调用耗时；尚无数据时为 {@code null}
 * @param authorized 本次工具调用是否通过授权复核
 * @param errorMessage 尚待服务端脱敏的受控错误说明；无错误时为空字符串
 */
public record ToolCallRecord(
        UUID toolId,
        String toolCallId,
        String toolName,
        String inputSummary,
        String outputSummary,
        RunStatus status,
        Duration duration,
        boolean authorized,
        String errorMessage
) {

    /**
     * 保留未携带 AgentScope 调用标识的旧 Runtime 构造方式。
     *
     * <p>旧实现仍可生成运行记录和持久化工具调用；会话执行轨迹只有在运行时提供稳定
     * {@code toolCallId} 时才能把输入和结果合并为同一张工具卡片。</p>
     *
     * @param toolId 被调用工具标识
     * @param toolName 工具名称快照
     * @param inputSummary 已限长的输入快照，服务端持久化前会脱敏
     * @param outputSummary 已限长的输出快照，服务端持久化前会脱敏
     * @param status 工具调用终态
     * @param duration 工具调用耗时
     * @param authorized 本次调用是否已授权
     * @param errorMessage 受控错误信息，服务端持久化前会脱敏
     */
    public ToolCallRecord(
            UUID toolId,
            String toolName,
            String inputSummary,
            String outputSummary,
            RunStatus status,
            Duration duration,
            boolean authorized,
            String errorMessage
    ) {
        this(toolId, "", toolName, inputSummary, outputSummary, status, duration, authorized, errorMessage);
    }
}
