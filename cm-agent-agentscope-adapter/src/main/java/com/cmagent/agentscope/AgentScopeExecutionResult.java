package com.cmagent.agentscope;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;

import java.util.List;
import java.util.Objects;

/**
 * AgentScope 执行阶段与 CM Agent 领域结果之间的终态快照。
 *
 * <p>该类型仅在适配器内部流转：执行器负责把 AgentScope 事件和工具调用记录归并到此处，
 * {@link AgentScopeRuntimeAdapter} 再补充运行标识与起止时间，形成对外的领域结果。将中间表示限制在包内，
 * 可以避免 Core 模块依赖任何 AgentScope 类型。</p>
 *
 * @param status 已完成运行的终态
 * @param output 可安全返回的模型输出；无输出时使用空字符串
 * @param toolCalls 本次运行已经完成记录的工具调用快照
 * @param errorMessage 可安全返回的错误说明；无错误时使用空字符串
 */
record AgentScopeExecutionResult(
        RunStatus status,
        String output,
        List<ToolCallRecord> toolCalls,
        String errorMessage
) {

    /**
     * 维护执行结果的终态与不可变性约束。
     *
     * <p>{@link RunStatus#RUNNING} 只属于上层持久化流程，不允许从已经结束的 AgentScope 执行阶段返回；
     * 工具记录使用防御性复制，避免异步事件收集器在结果返回后继续改变调用方看到的内容。</p>
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
     * 创建包含最终模型输出的成功结果。
     *
     * @param output 最终模型输出
     * @param toolCalls 本次运行产生的工具调用记录
     * @return 成功终态快照
     */
    static AgentScopeExecutionResult succeeded(String output, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.SUCCEEDED, output, toolCalls, "");
    }

    /**
     * 创建不向调用方暴露部分模型输出的失败结果。
     *
     * @param errorMessage 已脱敏、可对外返回的错误说明
     * @param toolCalls 失败前已经完成记录的工具调用
     * @return 失败终态快照
     */
    static AgentScopeExecutionResult failed(String errorMessage, List<ToolCallRecord> toolCalls) {
        return new AgentScopeExecutionResult(RunStatus.FAILED, "", toolCalls, errorMessage);
    }

    /**
     * 创建无模型输出的授权拒绝结果。
     *
     * @param errorMessage 已脱敏、可对外返回的拒绝原因
     * @param toolCalls 包含拒绝记录的工具调用快照
     * @return 拒绝终态快照
     */
    static AgentScopeExecutionResult denied(String errorMessage, List<ToolCallRecord> toolCalls) {
        return denied("", errorMessage, toolCalls);
    }

    /**
     * 创建可保留 AgentScope 最终文本的授权拒绝结果。
     *
     * <p>即使模型在工具被拒绝后仍生成了说明文本，运行终态也必须保持 {@link RunStatus#DENIED}，
     * 不能被最终消息误判为成功。</p>
     *
     * @param output 工具被拒绝后模型可能生成的最终说明
     * @param errorMessage 已脱敏、可对外返回的拒绝原因
     * @param toolCalls 包含拒绝记录的工具调用快照
     * @return 拒绝终态快照
     */
    static AgentScopeExecutionResult denied(
            String output,
            String errorMessage,
            List<ToolCallRecord> toolCalls
    ) {
        return new AgentScopeExecutionResult(RunStatus.DENIED, output, toolCalls, errorMessage);
    }
}
