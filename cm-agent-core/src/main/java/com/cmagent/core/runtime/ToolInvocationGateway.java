package com.cmagent.core.runtime;

/**
 * 定义 Agent 运行时调用受治理工具的统一入口。
 *
 * <p>这是工具治理的关键边界：模型运行链路上的每次工具调用都必须经过该网关，
 * 由实现方完成租户一致性、Agent 授权、风险策略与审计复核后才能执行，
 * 不允许信任模型提交的工具名称或直接根据工具定义中的 endpoint 发起调用。</p>
 */
public interface ToolInvocationGateway {

    /**
     * 通过治理入口调用工具并返回授权及执行结果。
     *
     * <p>普通授权拒绝与工具执行失败以 {@link ToolInvocationResult} 的 denied/failed 状态返回；
     * 审计、持久化等基础设施失败必须抛出 {@link ToolInvocationInfrastructureException}，
     * 不得降级为普通工具失败。</p>
     *
     * @param request 携带可信租户、主体与运行上下文的治理调用请求
     * @return 包含授权状态与执行状态的结果
     */
    ToolInvocationResult invoke(ToolInvocationRequest request);
}
