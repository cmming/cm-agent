package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentRunResult(
        UUID runId,
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {

    public AgentRunResult {
        toolCalls = List.copyOf(toolCalls);
    }
}
