package com.cmagent.core.tool;

@FunctionalInterface
/**
 * 定义单个工具执行器接收请求并返回结果的统一契约。
 */
public interface ToolExecutor {

    /**
     * 执行工具请求并返回统一结果。
      *
      * @param request 当前运行或工具调用请求
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
}
