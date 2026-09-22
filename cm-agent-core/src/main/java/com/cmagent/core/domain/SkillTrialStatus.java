package com.cmagent.core.domain;

/** 表示指定技能版本试运行的生命周期状态。 */
public enum SkillTrialStatus {
    /** 真实模型运行正在执行。 */
    RUNNING,
    /** 高风险工具调用正在等待人工确认。 */
    WAITING_APPROVAL,
    /** 运行成功且技能在执行中被实际加载。 */
    PASSED,
    /** 运行成功但目标技能没有被模型加载，不能作为发布依据。 */
    NOT_TRIGGERED,
    /** 运行或治理检查失败。 */
    FAILED
}
