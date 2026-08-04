package com.cmagent.core.tool;

/**
 * 标识工具调用来自 Agent 运行、调试接口、MCP 端点或兼容链路。
 */
public enum ToolInvocationSource {
    AGENT,
    DEBUG,
    MCP,
    LEGACY
}
