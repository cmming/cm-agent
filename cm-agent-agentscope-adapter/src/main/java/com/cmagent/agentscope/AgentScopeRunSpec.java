package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * AgentScope 执行阶段对领域运行请求的只读视图。
 *
 * <p>该类型不复制租户、主体或运行标识，而是统一从已经完成领域校验的
 * {@link AgentRunRequest} 派生常用字段，避免适配层维护多份可能不一致的安全上下文。</p>
 *
 * @param request 已校验且包含本次可见工具集合的领域运行请求
 */
public record AgentScopeRunSpec(AgentRunRequest request) {

    /**
     * 确保所有派生字段始终有可信的领域请求来源。
     */
    public AgentScopeRunSpec {
        Objects.requireNonNull(request, "request 不能为空");
    }

    /**
     * 返回本次运行的全局关联标识。
     *
     * @return 用作 AgentScope 会话标识和工具调用关联键的运行标识
     */
    public UUID runId() {
        return request.runId();
    }

    /**
     * 返回领域请求已经校验的租户边界。
     *
     * @return 来自领域请求的可信租户标识
     */
    public UUID tenantId() {
        return request.tenantId();
    }

    /**
     * 返回本次运行绑定的 Agent 标识。
     *
     * @return 当前运行的 Agent 标识
     */
    public UUID agentId() {
        return request.agent().id();
    }

    /**
     * 返回发起本次运行的认证主体标识。
     *
     * @return 发起运行的认证主体标识
     */
    public String principalId() {
        return request.principal().principalId();
    }

    /**
     * 返回待发送给 AgentScope 的用户输入。
     *
     * @return 将被包装为 AgentScope 用户消息的原始领域输入
     */
    public String userInput() {
        return request.input();
    }
}
