package com.cmagent.core.tool;

/**
 * 描述工具执行是否成功、输出摘要、状态码与错误信息。
 *
 * <p>输出摘要与错误说明在进入该对象前必须已按调用边界脱敏；{@code statusCode} 仅在外部服务
 * 返回 HTTP 状态时携带，非 HTTP 执行路径固定为 {@code null}。</p>
 *
 * @param outputSummary 已脱敏的工具输出摘要
 * @param success 执行是否成功
 * @param statusCode 外部服务返回的 HTTP 状态码；非 HTTP 执行路径为 {@code null}
 * @param errorMessage 已脱敏的错误说明；成功或未提供时为空字符串
 */
public record ToolExecutionResult(
        String outputSummary,
        boolean success,
        Integer statusCode,
        String errorMessage
) {

    /**
     * 创建兼容旧调用方式的工具执行结果。
     *
     * <p>两参构造无法携带外部服务 HTTP 状态码，状态码按“不存在”处理（{@code null}）。</p>
     *
     * @param outputSummary 已脱敏的工具输出摘要
     * @param success 本次处理是否成功
     */
    public ToolExecutionResult(String outputSummary, boolean success) {
        this(outputSummary, success, null, "");
    }

    /**
     * 创建成功的工具执行结果。
     *
     * @param outputSummary 已脱敏的工具输出摘要
     * @param statusCode 外部服务返回的 HTTP 状态码；非 HTTP 工具或状态未知时可为 {@code null}
     * @return 成功结果，错误信息为空字符串
     */
    public static ToolExecutionResult succeeded(String outputSummary, Integer statusCode) {
        return new ToolExecutionResult(outputSummary, true, statusCode, "");
    }

    /**
     * 创建失败的工具执行结果。
     *
     * @param errorMessage 已控制敏感信息的错误说明
     * @param statusCode 外部服务返回的 HTTP 状态码；非 HTTP 工具或状态未知时可为 {@code null}
     * @return 失败结果，输出摘要为空字符串
     */
    public static ToolExecutionResult failed(String errorMessage, Integer statusCode) {
        return new ToolExecutionResult("", false, statusCode, errorMessage);
    }
}
