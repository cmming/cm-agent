package com.cmagent.core.domain;

/**
 * 枚举平台支持的本地、HTTP 和 MCP 工具类型。
 */
public enum ToolType {
    /** 本地工具：在同一 Server JVM 内实现并注册 {@code ToolExecutor}。 */
    LOCAL,

    /** MCP 工具：通过 MCP 协议调用外部服务端点。 */
    MCP,

    /** A2A 工具：Agent-to-Agent 协议调用，当前为预留类型，尚未接入执行治理。 */
    A2A,

    /** 动态 HTTP 工具：按配置模板与参数映射发起受白名单约束的 HTTP 请求。 */
    HTTP
}
