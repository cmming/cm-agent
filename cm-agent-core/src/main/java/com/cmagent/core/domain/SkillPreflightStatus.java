package com.cmagent.core.domain;

/** 表示一次技能依赖预检的汇总结果。 */
public enum SkillPreflightStatus {
    /** 全部依赖均已就绪。 */
    PASSED,
    /** 必需依赖已就绪，但存在可选依赖告警。 */
    PASSED_WITH_WARNINGS,
    /** 至少一个必需依赖未满足。 */
    FAILED
}
