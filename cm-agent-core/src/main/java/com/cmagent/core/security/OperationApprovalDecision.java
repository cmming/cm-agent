package com.cmagent.core.security;

/** 操作确认策略在调用治理边界可观察的结果。 */
public enum OperationApprovalDecision {
    /** 无需增加人工确认，但执行前仍须复核所有授权与运行状态。 */
    ALLOW,
    /** 必须取得当前具体调用的人工确认后才能继续。 */
    ASK,
    /** 不允许执行，不能通过跳过审批或替换模式降级为允许。 */
    DENY
}
