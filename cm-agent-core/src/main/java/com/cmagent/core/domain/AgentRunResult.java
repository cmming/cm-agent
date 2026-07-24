package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AgentRunResult 的核心领域类型。
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
     * 构造 AgentRunResult 实例并校验输入参数。
     */
    public AgentRunResult {
        toolCalls = List.copyOf(toolCalls);
    }
}
