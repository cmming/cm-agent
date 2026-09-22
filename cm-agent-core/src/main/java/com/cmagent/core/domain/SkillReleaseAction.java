package com.cmagent.core.domain;

/** 定义追加式技能发布记录的业务动作。 */
public enum SkillReleaseAction {
    /** 将迁移前的当前版本登记为初始发布事实。 */
    BASELINE,
    /** 将当前候选版本正式发布。 */
    PUBLISH,
    /** 将曾发布的历史版本重新发布为当前版本。 */
    ROLLBACK
}
