package com.cmagent.core.tool;

@FunctionalInterface
public interface ToolExecutor {

    ToolExecutionResult execute(ToolExecutionRequest request);
}
