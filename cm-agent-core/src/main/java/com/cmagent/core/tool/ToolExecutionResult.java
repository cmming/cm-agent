package com.cmagent.core.tool;

public record ToolExecutionResult(
        String outputSummary,
        boolean success,
        Integer statusCode,
        String errorMessage
) {

    public ToolExecutionResult(String outputSummary, boolean success) {
        this(outputSummary, success, null, "");
    }

    public static ToolExecutionResult succeeded(String outputSummary, Integer statusCode) {
        return new ToolExecutionResult(outputSummary, true, statusCode, "");
    }

    public static ToolExecutionResult failed(String errorMessage, Integer statusCode) {
        return new ToolExecutionResult("", false, statusCode, errorMessage);
    }
}
