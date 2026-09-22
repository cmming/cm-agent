package com.cmagent.core.domain;

/** 标识依赖预检发生的治理入口。 */
public enum SkillPreflightScope {
    /** 只校验技能包声明及映射结构。 */
    STRUCTURAL,
    /** 针对一个 Agent 校验工具授权和可用性。 */
    AGENT,
    /** 发布前覆盖全部跟随型 Agent 的即时检查。 */
    PUBLISH,
    /** 正式绑定或切换绑定策略前的检查。 */
    BINDING,
    /** 依赖映射变更后用于确认影响的检查。 */
    MAPPING_CHANGE
}
