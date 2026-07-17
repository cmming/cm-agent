package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;

import java.util.List;
import java.util.Objects;

record AgentScopeExecutionResult(
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        String errorMessage
) {

    AgentScopeExecutionResult {
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(output, "output 不能为空");
        Objects.requireNonNull(toolCalls, "toolCalls 不能为空");
        Objects.requireNonNull(errorMessage, "errorMessage 不能为空");
        if (status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("执行结果必须是终态");
        }
        toolCalls = List.copyOf(toolCalls);
    }

    static AgentScopeExecutionResult succeeded(String output, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.SUCCEEDED, output, toolCalls, "");
    }

    static AgentScopeExecutionResult failed(String errorMessage, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.FAILED, "", toolCalls, errorMessage);
    }

    static AgentScopeExecutionResult denied(String errorMessage, List<ToolCallRecord> toolCalls) {
        return denied("", errorMessage, toolCalls);
    }

    static AgentScopeExecutionResult denied(
            String output,
            String errorMessage,
            List<ToolCallRecord> toolCalls
    ) {
        return new AgentScopeExecutionResult(RunStatus.DENIED, output, toolCalls, errorMessage);
    }
}
