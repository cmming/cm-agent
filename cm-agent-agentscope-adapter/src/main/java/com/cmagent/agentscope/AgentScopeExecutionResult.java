package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;

import java.util.List;
import java.util.Objects;

/** AgentScope 执行结果的适配器内部表示。 */
record AgentScopeExecutionResult(
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        String errorMessage
) {

    /** 校验执行结果并复制工具调用记录，确保结果不可变。 */
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

    /** 创建成功结果。 */
    static AgentScopeExecutionResult succeeded(String output, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.SUCCEEDED, output, toolCalls, "");
    }

    /** 创建失败结果。 */
    static AgentScopeExecutionResult failed(String errorMessage, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.FAILED, "", toolCalls, errorMessage);
    }

    /** 创建无输出的拒绝结果。 */
    static AgentScopeExecutionResult denied(String errorMessage, List<ToolCallRecord> toolCalls) {
        return denied("", errorMessage, toolCalls);
    }

    /** 创建可携带部分输出的拒绝结果。 */
    static AgentScopeExecutionResult denied(
            String output,
            String errorMessage,
            List<ToolCallRecord> toolCalls
    ) {
        return new AgentScopeExecutionResult(RunStatus.DENIED, output, toolCalls, errorMessage);
    }
}
