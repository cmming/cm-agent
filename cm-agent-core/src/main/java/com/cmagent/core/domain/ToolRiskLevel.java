package com.cmagent.core.domain;

/**
 * 枚举工具调用的风险等级，供授权与审计策略使用。
 */
public enum ToolRiskLevel {
    /** 低风险：常规读写工具，按默认治理策略调用与审计。 */
    LOW,

    /** 中风险：影响面较大的工具，授权拒绝与失败计入审计详情。 */
    MEDIUM,

    /** 高风险：控制台调试必须输入与工具名称完全一致的二次确认后才能执行。 */
    HIGH
}
