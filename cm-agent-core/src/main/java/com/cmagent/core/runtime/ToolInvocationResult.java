package com.cmagent.core.runtime;

/**
 * 描述受治理工具调用的输出、执行状态、授权状态和错误信息。
 */
public record ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage) {

    /**
     * 规范化工具调用输出和错误文本，避免结果中出现空引用。
      *
      * @param output 模型或工具输出
      * @param success 本次处理是否成功
      * @param authorized 本次工具调用是否通过授权
      * @param errorMessage 已控制敏感信息的错误说明
     */
    public ToolInvocationResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 创建已授权且执行成功的调用结果。
      *
     * @param output 模型或工具输出
     * @return 成功结果
     */
    public static ToolInvocationResult succeeded(String output) {
        return new ToolInvocationResult(output, true, true, "");
    }

    /**
     * 创建已授权但执行失败的调用结果。
      *
     * @param errorMessage 已控制敏感信息的错误说明
     * @return 执行失败结果
     */
    public static ToolInvocationResult failed(String errorMessage) {
        return new ToolInvocationResult("", false, true, errorMessage);
    }

    /**
     * 创建未通过授权校验的调用结果。
      *
     * @param reason 拒绝调用的原因
     * @return 授权拒绝结果
     */
    public static ToolInvocationResult denied(String reason) {
        return new ToolInvocationResult("", false, false, reason);
    }
}
