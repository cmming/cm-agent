package com.cmagent.core.domain;

/** 会话消息角色，语义与 AgentScope {@code MsgRole} 对齐但不引入框架依赖。 */
public enum MessageRole {
    /** 用户输入消息，只允许包含 TEXT 内容块。 */
    USER,

    /** 模型回复消息，允许文本与工具摘要块混合表达。 */
    ASSISTANT,

    /** 系统消息，只允许包含 TEXT 内容块。 */
    SYSTEM,

    /** 显式工具结果消息，只允许 TOOL_RESULT 块；当前 Web API 不允许客户端直接构造。 */
    TOOL
}
