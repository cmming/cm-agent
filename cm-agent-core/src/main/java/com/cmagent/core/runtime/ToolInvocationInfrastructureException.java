package com.cmagent.core.runtime;

/**
 * 表示工具调用链路中的网络、存储或运行时基础设施故障。
 */
public class ToolInvocationInfrastructureException extends RuntimeException {

    /**
     * 创建工具基础设施异常并保留原始原因。
      *
      * @param message 待记录或返回的消息文本
      * @param cause 触发当前异常的原始原因
     */
    public ToolInvocationInfrastructureException(String message, Throwable cause) {
        super(requireMessage(message), cause);
    }

    /**
     * 校验基础设施异常必须携带可诊断且非空的消息。
      *
     * @param message 待记录或返回的消息文本
     * @return 已校验的异常消息
     */
    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("工具调用基础设施失败消息不能为空");
        }
        return message;
    }
}
