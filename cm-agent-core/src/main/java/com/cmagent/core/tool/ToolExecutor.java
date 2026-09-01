package com.cmagent.core.tool;

/**
 * 定义单个工具执行器接收请求并返回结果的统一契约。
 *
 * <p>实现方应当只在受治理链路中被调用：普通工具允许返回失败结果，调用方据此把错误反馈给模型；
 * 审计或持久化等基础设施失败则应抛出 {@code ToolInvocationInfrastructureException}，不得伪装成工具失败。</p>
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具请求并返回统一结果。
     *
     * <p>实现不得抛出未受控异常来表达普通业务失败；请求中的 {@code inputJson} 可能包含用户输入，
     * 输出与错误信息必须先脱敏，避免原始参数或外部响应进入模型上下文。</p>
     *
     * @param request 当前工具执行请求，含可信租户、主体与调用来源
     * @return 已脱敏的执行结果；普通失败以 {@code success=false} 表达而非抛异常
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
}
