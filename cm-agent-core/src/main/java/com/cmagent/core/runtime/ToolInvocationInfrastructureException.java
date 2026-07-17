package com.cmagent.core.runtime;

public class ToolInvocationInfrastructureException extends RuntimeException {

    public ToolInvocationInfrastructureException(String message, Throwable cause) {
        super(requireMessage(message), cause);
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("工具调用基础设施失败消息不能为空");
        }
        return message;
    }
}
