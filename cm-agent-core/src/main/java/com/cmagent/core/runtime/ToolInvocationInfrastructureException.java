package com.cmagent.core.runtime;

/**
 * 表示工具调用链路中的网络、存储或运行时基础设施故障。
 *
 * <p>该异常与普通工具失败的意义不同：它表示治理网关之后的支撑设施不可用，
 * 必须向上传播并由 Server 的严格失败边界处理，运行不能被伪装为成功或普通失败；
 * 消息文本必须可诊断但已脱敏，不包含原始堆栈、密钥或内部实现细节。</p>
 */
public class ToolInvocationInfrastructureException extends RuntimeException {

    /**
     * 创建工具基础设施异常并保留原始原因。
     *
     * <p>消息不能为空白：基础设施失败必须能通过消息快速定位故障点。</p>
     *
     * @param message 已脱敏且可诊断的失败消息
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
     * @throws IllegalArgumentException 消息为空或空白时抛出
     */
    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("工具调用基础设施失败消息不能为空");
        }
        return message;
    }
}
