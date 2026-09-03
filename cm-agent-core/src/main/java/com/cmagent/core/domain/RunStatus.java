package com.cmagent.core.domain;

/**
 * 枚举 Agent 运行及工具调用记录的生命周期状态。
 */
public enum RunStatus {
    /** 运行或工具调用已启动但尚未收口；持久化记录处于该状态时不允许出现完成时间。 */
    RUNNING,

    /** AgentScope 已暂停并等待人工审批；该状态仍是非终态，不允许出现完成时间。 */
    WAITING_APPROVAL,

    /** 正常完成，输出摘要已脱敏。 */
    SUCCEEDED,

    /** 执行失败，错误说明已脱敏；普通工具失败不影响运行本身可能成功。 */
    FAILED,

    /** 被授权策略拒绝；作为独立终态与失败区分，便于审计区分越权尝试与执行故障。 */
    DENIED;

    /**
     * 判断状态是否仍可能继续推进。
     *
     * @return RUNNING 或 WAITING_APPROVAL 时返回 {@code true}
     */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_APPROVAL;
    }
}
