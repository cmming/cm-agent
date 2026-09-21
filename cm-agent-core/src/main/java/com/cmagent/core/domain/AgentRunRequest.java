package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 封装一次 Agent 运行所需的定义、模型、主体、输入和授权工具。
 *
 * @param runId 运行唯一标识，同时作为无会话运行时的 sessionId
 * @param tenantId 运行归属租户，全部参与对象的租户一致性以此为基准
 * @param agent 被运行的 Agent 定义
 * @param modelConfig 本次运行使用的模型配置，须与 Agent 绑定一致
 * @param principal 发起运行的认证主体
 * @param input 用户输入文本
 * @param tools 预筛选后的授权工具集合，构造时逐个校验租户归属
 * @param conversationId 可选的持久化会话标识；为空表示单轮无状态运行
 * @param skills 本次运行已固定版本的技能集合，不包含未绑定或停用技能
 */
public record AgentRunRequest(
        UUID runId,
        UUID tenantId,
        AgentDefinition agent,
        ModelConfig modelConfig,
        PrincipalRef principal,
        String input,
        List<ToolDefinition> tools,
        UUID conversationId,
        List<SkillVersionView> skills
) {

    /** 保留既有单轮调用方式；未提供会话时 Runtime 继续使用 runId 作为 sessionId。 */
    public AgentRunRequest(
            UUID runId,
            UUID tenantId,
            AgentDefinition agent,
            ModelConfig modelConfig,
            PrincipalRef principal,
            String input,
            List<ToolDefinition> tools
    ) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, null, List.of());
    }

    /** 保留既有带会话调用方式；未显式提供技能时使用空技能集合。 */
    public AgentRunRequest(
            UUID runId,
            UUID tenantId,
            AgentDefinition agent,
            ModelConfig modelConfig,
            PrincipalRef principal,
            String input,
            List<ToolDefinition> tools,
            UUID conversationId
    ) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, List.of());
    }

    /**
     * 校验运行请求的租户、主体、模型绑定和工具集合是否一致。
     *
     * <p>该构造器是运行入口的租户一致性总闸：Agent、模型配置、主体和每个授权工具
     * 必须全部属于 {@code tenantId}，且 Agent 绑定的模型配置 ID 必须与实际传入的
     * 模型配置一致。任何不一致在进入 Runtime 之前即失败，防止跨租户运行。</p>
     *
     * @param runId 目标运行标识
     * @param tenantId 当前租户标识
     * @param agent 当前 Agent 定义
     * @param modelConfig Agent 绑定的模型配置
     * @param principal 当前认证主体
     * @param input 调用方输入
     * @param tools 本次运行授权的工具集合
     * @param conversationId 可选的持久化会话标识
     * @param skills 本次运行固定版本的技能集合
     */
    public AgentRunRequest {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agent, "agent 不能为空");
        Objects.requireNonNull(modelConfig, "modelConfig 不能为空");
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(tools, "tools 不能为空");
        Objects.requireNonNull(skills, "skills 不能为空");
        tools = List.copyOf(tools);
        skills = List.copyOf(skills);
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
        java.util.Set<String> skillNames = new java.util.HashSet<>();
        for (SkillVersionView skill : skills) {
            if (!tenantId.equals(skill.definition().tenantId())) {
                throw new IllegalArgumentException("技能版本不属于当前租户");
            }
            if (!skillNames.add(skill.definition().name())) {
                throw new IllegalArgumentException("运行技能名称不能重复");
            }
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
