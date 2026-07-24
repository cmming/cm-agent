package com.cmagent.core.tool;

@FunctionalInterface
/**
 * ToolExecutor 的核心领域类型。
 */
public interface ToolExecutor {

    /**
     * 定义 execute 操作。
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
}
