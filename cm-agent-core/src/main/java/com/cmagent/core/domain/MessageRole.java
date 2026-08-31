package com.cmagent.core.domain;

/** 会话消息角色，语义与 AgentScope {@code MsgRole} 对齐但不引入框架依赖。 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}
