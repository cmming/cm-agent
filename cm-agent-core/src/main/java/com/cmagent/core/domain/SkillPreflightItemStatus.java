package com.cmagent.core.domain;

/** 表示单个逻辑工具依赖的预检结果。 */
public enum SkillPreflightItemStatus {
    /** 映射、工具状态和 Agent 授权全部有效。 */
    READY,
    /** 可选依赖未满足，允许流程继续。 */
    OPTIONAL_WARNING,
    /** 当前租户尚未为逻辑依赖配置工具映射。 */
    MAPPING_MISSING,
    /** 映射工具不存在、停用或运行时未就绪。 */
    TOOL_UNAVAILABLE,
    /** 目标 Agent 未获得映射工具的调用授权。 */
    GRANT_MISSING
}
