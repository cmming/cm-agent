package com.cmagent.core.runtime;

/**
 * ToolInvocationInfrastructureException 的核心领域类型。
 */
public class ToolInvocationInfrastructureException extends RuntimeException {

    /**
     * 构造 ToolInvocationInfrastructureException 实例并校验输入参数。
     */
    public ToolInvocationInfrastructureException(String message, Throwable cause) {
        super(requireMessage(message), cause);
    }

    /**
     * 执行 requireMessage 操作。
     */
    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("工具调用基础设施失败消息不能为空");
        }
        return message;
    }
}
