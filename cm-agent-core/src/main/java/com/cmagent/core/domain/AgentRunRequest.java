package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 封装一次 Agent 运行所需的定义、模型、主体、工具和固定技能上下文。
 *
 * @param runId 运行唯一标识，同时作为无会话运行时的 sessionId
 * @param tenantId 运行归属租户，全部参与对象的租户一致性以此为基准
 * @param agent 被运行的 Agent 定义
 * @param modelConfig 本次运行使用的模型配置，须与 Agent 绑定一致
 * @param principal 发起运行的认证主体
 * @param input 用户输入文本
 * @param tools 预筛选后的授权工具集合，构造时逐个校验租户归属
 * @param conversationId 可选的持久化会话标识；为空表示单轮或试运行
 * @param skills 本次运行已固定版本的技能集合
 * @param skillDependencies 按技能标识分组的固定依赖解析
 * @param cancellation 本运行独享的停止信号，由可信服务端创建，不接受客户端覆盖
 * @param workspaceContext 可选的可信只读参考快照；旧调用默认不携带，不授予文件或工具权限
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
        List<SkillVersionView> skills,
        Map<UUID, List<SkillDependencyResolution>> skillDependencies,
        com.cmagent.core.runtime.RunCancellation cancellation,
        WorkspaceContextSnapshot workspaceContext
) {
    /** 保留原十一参数调用；宿主未显式准备资料时不增加输入或状态。 */
    public AgentRunRequest(UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig,
            PrincipalRef principal, String input, List<ToolDefinition> tools, UUID conversationId,
            List<SkillVersionView> skills, Map<UUID, List<SkillDependencyResolution>> dependencies,
            com.cmagent.core.runtime.RunCancellation cancellation) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, skills, dependencies, cancellation, null);
    }
    /** 保留原十参数构造方式，嵌入式调用默认拥有独立且未请求停止的信号。 */
    public AgentRunRequest(UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig,
                           PrincipalRef principal, String input, List<ToolDefinition> tools, UUID conversationId,
                           List<SkillVersionView> skills, Map<UUID, List<SkillDependencyResolution>> skillDependencies) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, skills,
                skillDependencies, new com.cmagent.core.runtime.RunCancellation());
    }
    /** 保留既有七参数单轮调用方式。 */
    public AgentRunRequest(
            UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig,
            PrincipalRef principal, String input, List<ToolDefinition> tools
    ) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, null, List.of(), Map.of());
    }

    /** 保留既有八参数会话调用方式。 */
    public AgentRunRequest(
            UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig,
            PrincipalRef principal, String input, List<ToolDefinition> tools, UUID conversationId
    ) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, List.of(), Map.of());
    }

    /** 保留既有九参数技能调用方式，并默认没有逻辑依赖。 */
    public AgentRunRequest(
            UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig,
            PrincipalRef principal, String input, List<ToolDefinition> tools, UUID conversationId,
            List<SkillVersionView> skills
    ) {
        this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, skills, Map.of());
    }

    /**
     * 校验运行请求的租户、主体、模型绑定、工具以及技能依赖边界。
     *
     * <p>tenantId 只能由可信运行编排传入；任何跨租户对象都在进入可替换 Runtime 前拒绝。
     * 依赖映射已在服务端预检阶段解析，本构造器只冻结结果，不授予额外工具权限。</p>
     */
    public AgentRunRequest {
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agent, "agent 不能为空");
        Objects.requireNonNull(modelConfig, "modelConfig 不能为空");
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(input, "input 不能为空");
        if (workspaceContext != null && (!tenantId.equals(workspaceContext.tenantId())
                || !agent.id().equals(workspaceContext.agentId()) || !runId.equals(workspaceContext.runId())
                || !principal.principalId().equals(workspaceContext.principalId()))) {
            throw new IllegalArgumentException("参考快照不属于当前可信运行主体或 Agent");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools 不能为空"));
        skills = List.copyOf(Objects.requireNonNull(skills, "skills 不能为空"));
        skillDependencies = freezeDependencies(skillDependencies);
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
        Set<String> skillNames = new HashSet<>();
        Set<UUID> skillIds = new HashSet<>();
        for (SkillVersionView skill : skills) {
            if (!tenantId.equals(skill.definition().tenantId())) {
                throw new IllegalArgumentException("技能版本不属于当前租户");
            }
            if (!skillNames.add(skill.definition().name())) {
                throw new IllegalArgumentException("运行技能名称不能重复");
            }
            skillIds.add(skill.definition().id());
        }
        if (!skillIds.containsAll(skillDependencies.keySet())) {
            throw new IllegalArgumentException("技能依赖解析包含未加载技能");
        }
    }

    /** @return 当前运行绑定的 Agent 标识 */
    public UUID agentId() {
        return agent.id();
    }

    private static Map<UUID, List<SkillDependencyResolution>> freezeDependencies(
            Map<UUID, List<SkillDependencyResolution>> source
    ) {
        Objects.requireNonNull(source, "skillDependencies 不能为空");
        Map<UUID, List<SkillDependencyResolution>> copy = new LinkedHashMap<>();
        source.forEach((skillId, dependencies) -> copy.put(
                Objects.requireNonNull(skillId, "依赖解析技能标识不能为空"),
                List.copyOf(Objects.requireNonNull(dependencies, "依赖解析集合不能为空"))));
        return Map.copyOf(copy);
    }
}
