package com.cmagent.core.runtime;

public record ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage) {

    public ToolInvocationResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ToolInvocationResult succeeded(String output) {
        return new ToolInvocationResult(output, true, true, "");
    }

    public static ToolInvocationResult failed(String errorMessage) {
        return new ToolInvocationResult("", false, true, errorMessage);
    }

    public static ToolInvocationResult denied(String reason) {
        return new ToolInvocationResult("", false, false, reason);
    }
}
