package com.cmagent.core.domain;

/** 定义工具风险等级之外的运行时审批策略。 */
public enum ToolApprovalPolicy {
    /** 不触发人工审批，仍需经过现有工具授权和治理网关。 */
    NEVER,
    /** 每个具体工具调用及其参数都必须单独确认。 */
    EACH_CALL,
    /** 同一 Run 首次确认后可复用；第一版仅预留，不开放配置。 */
    ONCE_PER_RUN,
    /** 始终拒绝，人工审批不能覆盖该策略。 */
    ALWAYS_DENY
}
