package com.cmagent.core.tool;

/**
 * 标识工具调用来自 Agent 运行、调试接口、MCP 端点或兼容链路。
 *
 * <p>来源决定 {@link ToolExecutionRequest} 构造期上下文校验的严格程度：AGENT 与 DEBUG/MCP/LEGACY
 * 的必填字段组合不同，新增来源时必须同步调整该构造器校验与治理网关的授权规则。</p>
 */
public enum ToolInvocationSource {
    /** Agent 运行链路内的工具调用，必须绑定 agentId 与 runId 并进入运行历史。 */
    AGENT,

    /** 控制台单工具调试调用，不关联任何 Agent 或运行记录。 */
    DEBUG,

    /** 已发布工具经 MCP 端点被外部客户端调用，不关联 CM Agent 的运行记录。 */
    MCP,

    /** 携带完整运行上下文的兼容调用来源，仅保留给尚未接入治理编排的旧调用方过渡。 */
    LEGACY
}
