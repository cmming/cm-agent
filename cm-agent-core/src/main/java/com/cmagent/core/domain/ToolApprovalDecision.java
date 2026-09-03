package com.cmagent.core.domain;

/** 枚举审批人对单个待调用工具作出的决定。 */
public enum ToolApprovalDecision {
    /** 仅允许本次绑定参数的具体调用继续执行。 */
    APPROVE,
    /** 拒绝本次调用，不能扩大为永久禁止规则。 */
    DENY
}
