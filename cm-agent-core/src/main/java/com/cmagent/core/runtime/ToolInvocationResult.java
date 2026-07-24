package com.cmagent.core.runtime;

/**
 * ToolInvocationResult 的核心领域类型。
 */
public record ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage) {

    /**
     * 构造 ToolInvocationResult 实例并校验输入参数。
     */
    public ToolInvocationResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 执行 succeeded 操作。
     */
    public static ToolInvocationResult succeeded(String output) {
        return new ToolInvocationResult(output, true, true, "");
    }

    /**
     * 执行 failed 操作。
     */
    public static ToolInvocationResult failed(String errorMessage) {
        return new ToolInvocationResult("", false, true, errorMessage);
    }

    /**
     * 执行 denied 操作。
     */
    public static ToolInvocationResult denied(String reason) {
        return new ToolInvocationResult("", false, false, reason);
    }
}
