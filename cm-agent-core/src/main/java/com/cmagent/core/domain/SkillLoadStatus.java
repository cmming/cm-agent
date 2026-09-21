package com.cmagent.core.domain;

/** 技能正文读取的持久化结果。 */
public enum SkillLoadStatus {
    /** 正文已在记录和审计成功提交后交给模型。 */
    SUCCEEDED,
    /** 原生读取或基础设施失败，正文未交付。 */
    FAILED,
    /** 权限、撤销或预算策略拒绝读取，正文未交付。 */
    DENIED
}
