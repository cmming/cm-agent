package com.cmagent.core.domain;

/** 标识运行快照中技能版本的授权来源。 */
public enum SkillSnapshotOrigin {
    /** 来源于 Agent 的正式技能绑定。 */
    BINDING,
    /** 来源于一次指定版本试运行的临时注入。 */
    TRIAL
}
