package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 封装一次 Agent 运行所需的定义、模型、主体、输入和授权工具。
 */
public record AgentRunRequest(
        UUID runId,
        UUID tenantId,
        AgentDefinition agent,
        ModelConfig modelConfig,
        PrincipalRef principal,
        String input,
        List<ToolDefinition> tools
) {

    /**
     * 校验运行请求的租户、主体、模型绑定和工具集合是否一致。
      *
      * @param runId 目标运行标识
      * @param tenantId 当前租户标识
      * @param agent 当前 Agent 定义
      * @param modelConfig Agent 绑定的模型配置
      * @param principal 当前认证主体
      * @param input 调用方输入
      * @param tools 本次运行授权的工具集合
     */
    public AgentRunRequest {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agent, "agent 不能为空");
        Objects.requireNonNull(modelConfig, "modelConfig 不能为空");
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(tools, "tools 不能为空");
        tools = List.copyOf(tools);
        if (!tenantId.equals(agent.tenantId())) {
            throw new IllegalArgumentException("Agent 不属于当前租户");
        }
        if (!tenantId.equals(modelConfig.tenantId())) {
            throw new IllegalArgumentException("模型配置不属于当前租户");
        }
        UUID agentModelProviderId = Objects.requireNonNull(
                agent.modelProviderId(), "Agent 模型配置 ID 不能为空");
        UUID modelConfigId = Objects.requireNonNull(modelConfig.id(), "模型配置 ID 不能为空");
        if (!agentModelProviderId.equals(modelConfigId)) {
            throw new IllegalArgumentException("模型配置与 Agent 绑定不一致");
        }
        if (!tenantId.equals(principal.tenantId())) {
            throw new IllegalArgumentException("调用主体不属于当前租户");
        }
        if (tools.stream().anyMatch(tool -> !tenantId.equals(tool.tenantId()))) {
            throw new IllegalArgumentException("工具不属于当前租户");
        }
    }

    /**
     * 从当前 Agent 定义中取得 Agent 标识。
     *
     * @return 当前运行绑定的 Agent 标识
     */
    public UUID agentId() {
        return agent.id();
    }
}
