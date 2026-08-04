package com.cmagent.core.runtime;

@FunctionalInterface
/**
 * 定义 Agent 运行时调用受治理工具的统一入口。
 */
public interface ToolInvocationGateway {

    /**
     * 通过治理入口调用工具并返回授权及执行结果。
      *
      * @param request 当前运行或工具调用请求
     */
    ToolInvocationResult invoke(ToolInvocationRequest request);
}
