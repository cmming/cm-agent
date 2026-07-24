package com.cmagent.core.tool;

/**
 * ToolExecutionResult 的核心领域类型。
 */
public record ToolExecutionResult(
        String outputSummary,
        boolean success,
        Integer statusCode,
        String errorMessage
) {

    /**
     * 构造 ToolExecutionResult 实例并校验输入参数。
     */
    public ToolExecutionResult(String outputSummary, boolean success) {
        this(outputSummary, success, null, "");
    }

    /**
     * 执行 succeeded 操作。
     */
    public static ToolExecutionResult succeeded(String outputSummary, Integer statusCode) {
        return new ToolExecutionResult(outputSummary, true, statusCode, "");
    }

    /**
     * 执行 failed 操作。
     */
    public static ToolExecutionResult failed(String errorMessage, Integer statusCode) {
        return new ToolExecutionResult("", false, statusCode, errorMessage);
    }
}
