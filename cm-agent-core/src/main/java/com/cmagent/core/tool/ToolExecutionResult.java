package com.cmagent.core.tool;

/**
 * 描述工具执行是否成功、输出摘要、状态码与错误信息。
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
     * @param statusCode 外部服务返回的 HTTP 状态码
     * @return 成功结果
     */
    public static ToolExecutionResult succeeded(String outputSummary, Integer statusCode) {
        return new ToolExecutionResult(outputSummary, true, statusCode, "");
    }

    /**
     * 创建失败的工具执行结果。
      *
      * @param errorMessage 已控制敏感信息的错误说明
     * @param statusCode 外部服务返回的 HTTP 状态码
     * @return 失败结果
     */
    public static ToolExecutionResult failed(String errorMessage, Integer statusCode) {
        return new ToolExecutionResult("", false, statusCode, errorMessage);
    }
}
