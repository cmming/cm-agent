package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;

import java.util.List;
import java.util.Objects;

/**
 * AgentScope 执行结果的适配器内部表示。
 */
record AgentScopeExecutionResult(
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        String errorMessage
) {

    /**
     * 校验执行结果并复制工具调用记录，确保结果不可变。
      *
      * @param status 目标运行状态
      * @param output 模型或工具输出
      * @param toolCalls 本次运行产生的工具调用记录
      * @param errorMessage 已控制敏感信息的错误说明
     */
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

    /**
     * 创建成功结果。
      *
      * @param output 模型或工具输出
      * @param toolCalls 本次运行产生的工具调用记录
     */
    static AgentScopeExecutionResult succeeded(String output, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.SUCCEEDED, output, toolCalls, "");
    }

    /**
     * 创建失败结果。
      *
      * @param errorMessage 已控制敏感信息的错误说明
      * @param toolCalls 本次运行产生的工具调用记录
     */
    static AgentScopeExecutionResult failed(String errorMessage, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.FAILED, "", toolCalls, errorMessage);
    }

    /**
     * 创建无输出的拒绝结果。
      *
      * @param errorMessage 已控制敏感信息的错误说明
      * @param toolCalls 本次运行产生的工具调用记录
     */
    static AgentScopeExecutionResult denied(String errorMessage, List<ToolCallRecord> toolCalls) {
        return denied("", errorMessage, toolCalls);
    }

    /**
     * 创建可携带部分输出的拒绝结果。
      *
      * @param output 模型或工具输出
      * @param errorMessage 已控制敏感信息的错误说明
      * @param toolCalls 本次运行产生的工具调用记录
     */
    static AgentScopeExecutionResult denied(
            String output,
            String errorMessage,
            List<ToolCallRecord> toolCalls
    ) {
        return new AgentScopeExecutionResult(RunStatus.DENIED, output, toolCalls, errorMessage);
    }
}
