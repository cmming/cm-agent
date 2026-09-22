package com.cmagent.core.domain;

/** 定义 Agent 绑定技能时选择发布版本的策略。 */
public enum SkillBindingMode {
    /** 每次新运行解析技能当前已发布版本。 */
    FOLLOW_PUBLISHED,
    /** 每次新运行固定使用绑定记录指定的已发布历史版本。 */
    PINNED
}
