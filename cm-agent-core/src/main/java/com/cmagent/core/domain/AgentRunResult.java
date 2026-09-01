package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 描述 Agent 运行的终态、输出、工具调用记录和时间范围。
 *
 * @param runId 运行标识
 * @param status 运行终态
 * @param output 模型或工具的最终输出，已在适配器内脱敏
 * @param toolCalls 本次运行产生的工具调用摘要列表，不可变
 * @param startedAt 运行开始时间
 * @param finishedAt 运行完成时间
 * @param errorMessage 已脱敏的错误说明；成功时为空字符串
 */
public record AgentRunResult(
        UUID runId,
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {

    /**
     * 校验并规范化 Agent 运行结果、工具调用列表和时间范围。
     *
     * <p>运行结果属于 Runtime 输出边界：{@code output}、{@code errorMessage} 和工具摘要
     * 必须已在适配器内脱敏；工具调用列表防御性复制，防止保存后被调用方修改。</p>
     *
     * @param runId 目标运行标识
     * @param status 目标运行状态
     * @param output 模型或工具输出
     * @param toolCalls 本次运行产生的工具调用记录
     * @param startedAt 流程开始时间
     * @param finishedAt 流程完成时间
     * @param errorMessage 已控制敏感信息的错误说明
     */
    public AgentRunResult {
        toolCalls = List.copyOf(toolCalls);
    }
}
