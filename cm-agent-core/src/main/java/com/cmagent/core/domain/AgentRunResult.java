package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 描述 Agent 运行的终态、输出、工具调用记录和时间范围。
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
