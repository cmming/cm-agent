package com.cmagent.core.runtime;

/**
 * 受治理工具的模型数据与可持久化摘要合同。
 *
 * <p>成功、执行失败和授权拒绝仍保持原有语义。受保护项目工具的正文只进入output，
 * displayOutput/errorMessage必须由可信网关构造为安全元数据；标记不能来自模型或HTTP。
 * 旧四参构造器和工厂保留原行为，桥接器对已登记受保护工具还会拒绝旧结果回退正文摘要。</p>
 *
 * @param output 仅供本次授权模型循环使用的输出；失败/拒绝通常为空
 * @param success 工具是否执行成功
 * @param authorized 是否通过当前授权复核
 * @param errorMessage 已脱敏的固定错误或拒绝说明，不得放原始输出
 * @param displayOutput 可写入普通ToolCallRecord/进度的安全摘要，不得包含文件/补丁/终端正文
 * @param protectedPayload 是否由可信网关显式采用受保护载荷合同，不替代工具或项目授权
 */
public record ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage,
        String displayOutput, boolean protectedPayload) {

    /** 保留旧调用合同：旧输出仍同时作为显示摘要，不能用来构造受保护项目工具结果。 */
    public ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage) {
        this(output, success, authorized, errorMessage, output, false);
    }

    public ToolInvocationResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
        displayOutput = displayOutput == null ? "" : displayOutput;
    }

    /**
     * @param output 已脱敏的普通工具输出
     * @return 保持旧显示/模型同值的成功结果
     */
    public static ToolInvocationResult succeeded(String output) {
        return new ToolInvocationResult(output, true, true, "");
    }

    /**
     * 仅由已核验的治理网关创建，摘要不可直接复制正文。
     * @param modelOutput 授权模型需要的有界正文，不能进入普通记录
     * @param displayOutput 安全元数据摘要，例如操作编号/条目数/状态
     * @return 标记已采用正文/摘要分离的成功结果
     */
    public static ToolInvocationResult succeededProtected(String modelOutput, String displayOutput) {
        return new ToolInvocationResult(modelOutput, true, true, "", displayOutput, true);
    }

    /**
     * @param errorMessage 已脱敏普通错误
     * @return 保持原合同的失败结果
     */
    public static ToolInvocationResult failed(String errorMessage) {
        return new ToolInvocationResult("", false, true, errorMessage);
    }

    /**
     * @param safeError 受保护工具的安全业务原因或同号错误编号，不可用原始异常消息
     * @return 受控失败
     */
    public static ToolInvocationResult failedProtected(String safeError) {
        return new ToolInvocationResult("", false, true, safeError, safeError, true);
    }

    /**
     * @param reason 已脱敏普通拒绝原因
     * @return 保持原合同的拒绝结果
     */
    public static ToolInvocationResult denied(String reason) {
        return new ToolInvocationResult("", false, false, reason);
    }

    /**
     * @param safeReason 当前治理策略给出的安全拒绝说明
     * @return 受保护工具的拒绝结果
     */
    public static ToolInvocationResult deniedProtected(String safeReason) {
        return new ToolInvocationResult("", false, false, safeReason, safeReason, true);
    }

    /** 默认字符串不输出任何载荷，即使日志误引用整个结果也不把模型正文或错误细节写出。 */
    @Override public String toString() {
        return "ToolInvocationResult[success=" + success + ",authorized=" + authorized
                + ",protectedPayload=" + protectedPayload + "]";
    }
}
