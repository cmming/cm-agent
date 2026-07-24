package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;

import java.util.Objects;
import java.util.UUID;

/** AgentScope 执行所需的领域请求及其常用字段视图。 */
public record AgentScopeRunSpec(AgentRunRequest request) {

    /** 校验领域运行请求不为空。 */
    public AgentScopeRunSpec {
        Objects.requireNonNull(request, "request 不能为空");
    }

    /** 返回运行标识。 */
    public UUID runId() {
        return request.runId();
    }

    /** 返回租户标识。 */
    public UUID tenantId() {
        return request.tenantId();
    }

    /** 返回 Agent 标识。 */
    public UUID agentId() {
        return request.agent().id();
    }

    /** 返回当前主体标识。 */
    public String principalId() {
        return request.principal().principalId();
    }

    /** 返回用户输入。 */
    public String userInput() {
        return request.input();
    }
}
