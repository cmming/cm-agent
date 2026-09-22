package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightItemStatus;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillPreflightRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行技能依赖的结构预检与 Agent 预检，并持久化不可变结果。
 *
 * <p>预检顺序固定：先按可信租户读取不可变依赖声明和技能级映射，再通过
 * {@link ToolDefinitionRepository} 和 {@link ToolRuntimeReadiness} 判断工具是否进入既有受治理
 * 执行链路，最后按需核对 Agent 级 {@link ToolGrant}。预检只读取治理事实，不调用任何执行器，
 * 避免检查动作产生业务副作用。</p>
 */
@Service
public class SkillPreflightService {
    private final SkillDefinitionRepository definitions;
    private final SkillDependencyRepository dependencies;
    private final SkillDependencyMappingRepository mappings;
    private final SkillPreflightRepository preflights;
    private final ToolDefinitionRepository tools;
    private final HttpToolConfigRepository httpConfigs;
    private final ToolGrantRepository grants;
    private final AgentDefinitionRepository agents;
    private final AgentSkillBindingRepository bindings;
    private final ToolRuntimeReadiness readiness;
    private final SkillUnitOfWork workUnit;
    private final Clock clock;

    /** 创建依赖预检服务并固定治理事实来源。 */
    @Autowired
    public SkillPreflightService(
            SkillDefinitionRepository definitions,
            SkillDependencyRepository dependencies,
            SkillDependencyMappingRepository mappings,
            SkillPreflightRepository preflights,
            ToolDefinitionRepository tools,
            HttpToolConfigRepository httpConfigs,
            ToolGrantRepository grants,
            AgentDefinitionRepository agents,
            AgentSkillBindingRepository bindings,
            ToolRuntimeReadiness readiness,
            SkillUnitOfWork workUnit) {
        this(definitions, dependencies, mappings, preflights, tools, httpConfigs, grants, agents,
                bindings, readiness, workUnit, Clock.systemUTC());
    }

    SkillPreflightService(
            SkillDefinitionRepository definitions,
            SkillDependencyRepository dependencies,
            SkillDependencyMappingRepository mappings,
            SkillPreflightRepository preflights,
            ToolDefinitionRepository tools,
            HttpToolConfigRepository httpConfigs,
            ToolGrantRepository grants,
            AgentDefinitionRepository agents,
            AgentSkillBindingRepository bindings,
            ToolRuntimeReadiness readiness,
            SkillUnitOfWork workUnit,
            Clock clock) {
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies 不能为空");
        this.mappings = Objects.requireNonNull(mappings, "mappings 不能为空");
        this.preflights = Objects.requireNonNull(preflights, "preflights 不能为空");
        this.tools = Objects.requireNonNull(tools, "tools 不能为空");
        this.httpConfigs = Objects.requireNonNull(httpConfigs, "httpConfigs 不能为空");
        this.grants = Objects.requireNonNull(grants, "grants 不能为空");
        this.agents = Objects.requireNonNull(agents, "agents 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.readiness = Objects.requireNonNull(readiness, "readiness 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 对指定技能版本执行一次依赖预检并持久化结果。
     *
     * <p>{@code scope} 为 {@link SkillPreflightScope#AGENT} 或
     * {@link SkillPreflightScope#BINDING} 时必须提供 agentId；发布范围会自动枚举全部跟随型
     * Agent，并为每个 Agent 生成独立明细。所有租户条件均来自认证主体。</p>
     *
     * @param principal 当前认证主体，提供可信租户和发起人
     * @param skillId 目标技能标识
     * @param versionId 目标不可变版本标识
     * @param scope 预检治理入口
     * @param agentId 单 Agent 预检的目标；其他范围可为空
     * @return 已持久化的预检汇总及排序后的明细
     * @throws SkillAccessException 技能不存在、跨租户或 Agent 范围缺少目标时抛出
     */
    public SkillPreflightResult preflight(
            PrincipalRef principal, UUID skillId, UUID versionId,
            SkillPreflightScope scope, UUID agentId) {
        Objects.requireNonNull(scope, "scope 不能为空");
        UUID tenantId = principal.tenantId();
        SkillDefinition definition = definitions.find(tenantId, skillId).orElseThrow(this::notFound);
        if (!definition.enabled() && scope == SkillPreflightScope.TRIAL) {
            throw notFound();
        }
        List<UUID> targetAgents = resolveTargetAgents(tenantId, skillId, scope, agentId);
        List<SkillDependency> declared = dependencies.list(tenantId, skillId, versionId);
        Instant now = clock.instant();
        UUID checkId = UUID.randomUUID();
        List<SkillPreflightItem> items = new ArrayList<>();
        if (targetAgents.isEmpty()) {
            for (SkillDependency dependency : declared) {
                items.add(check(tenantId, checkId, dependency, null));
            }
        } else {
            for (UUID targetAgent : targetAgents) {
                requireRunnableAgent(tenantId, targetAgent);
                for (SkillDependency dependency : declared) {
                    items.add(check(tenantId, checkId, dependency, targetAgent));
                }
            }
        }
        SkillPreflightStatus status = summarize(definition.enabled(), items);
        SkillPreflightCheck check = new SkillPreflightCheck(
                checkId, tenantId, skillId, versionId, definition.dependencyMappingRevision(),
                scope, scope == SkillPreflightScope.PUBLISH ? null : agentId,
                status, principal.principalId(), now);
        // 预检结果本身属于治理证据，必须与业务状态同事务写入；
        // memory 实现的仓储会拒绝工作单元之外的写入。
        return new SkillPreflightResult(
                workUnit.execute(() -> preflights.save(check, items)), List.copyOf(items));
    }

    private List<UUID> resolveTargetAgents(
            UUID tenantId, UUID skillId, SkillPreflightScope scope, UUID agentId) {
        return switch (scope) {
            case STRUCTURAL -> List.of();
            case AGENT, BINDING, MAPPING_CHANGE, TRIAL -> List.of(requireAgentId(agentId));
            case PUBLISH -> bindings.listFollowerAgentIds(tenantId, skillId);
        };
    }

    private UUID requireAgentId(UUID agentId) {
        if (agentId == null) {
            throw new SkillAccessException(ApiErrorCode.VALIDATION_FAILED,
                    "当前预检场景必须指定 agentId", errorId(), false);
        }
        return agentId;
    }

    private void requireRunnableAgent(UUID tenantId, UUID agentId) {
        Optional<AgentDefinition> agent = agents.findByTenantAndId(tenantId, agentId);
        if (agent.isEmpty() || !agent.get().enabled()) {
            throw new SkillAccessException(ApiErrorCode.SKILL_AGENT_GRANT_MISSING,
                    "Agent 不存在、已停用或不属于当前租户", errorId(), false);
        }
    }

    private SkillPreflightItem check(
            UUID tenantId, UUID checkId, SkillDependency dependency, UUID agentId) {
        Optional<SkillDependencyMapping> mapping =
                mappings.find(tenantId, dependency.skillId(), dependency.logicalKey());
        if (mapping.isEmpty()) {
            return item(checkId, agentId, dependency, null,
                    dependency.required() ? SkillPreflightItemStatus.MAPPING_MISSING
                            : SkillPreflightItemStatus.OPTIONAL_WARNING,
                    ApiErrorCode.SKILL_DEPENDENCY_UNMAPPED,
                    dependency.required() ? "必需依赖尚未映射到租户工具" : "可选依赖尚未映射到租户工具");
        }
        UUID toolId = mapping.get().toolId();
        Optional<ToolDefinition> tool = tools.findByTenantAndId(tenantId, toolId);
        if (tool.isEmpty()) {
            return unavailable(checkId, agentId, dependency, toolId, "映射工具不存在或已被删除");
        }
        HttpToolConfig config = httpConfigs.findByTenantAndToolId(tenantId, toolId).orElse(null);
        if (!readiness.isReady(tool.get(), config)) {
            return unavailable(checkId, agentId, dependency, toolId, "映射工具未启用或运行配置不完整");
        }
        if (agentId != null) {
            List<ToolGrant> agentGrants = grants.listByTenantAgentAndTool(tenantId, agentId, toolId);
            boolean granted = agentGrants.stream().anyMatch(grant -> grant.granted()
                    && grant.tenantId().equals(tenantId)
                    && grant.agentId().equals(agentId)
                    && grant.toolId().equals(toolId));
            if (!granted) {
                return item(checkId, agentId, dependency, toolId,
                        SkillPreflightItemStatus.GRANT_MISSING,
                        ApiErrorCode.SKILL_AGENT_GRANT_MISSING, "目标 Agent 未获得映射工具的现行授权");
            }
        }
        return item(checkId, agentId, dependency, toolId, SkillPreflightItemStatus.READY, null, "依赖已就绪");
    }

    private SkillPreflightItem unavailable(
            UUID checkId, UUID agentId, SkillDependency dependency, UUID toolId, String message) {
        return item(checkId, agentId, dependency, toolId,
                dependency.required() ? SkillPreflightItemStatus.TOOL_UNAVAILABLE
                        : SkillPreflightItemStatus.OPTIONAL_WARNING,
                ApiErrorCode.SKILL_DEPENDENCY_UNAVAILABLE, message);
    }

    private SkillPreflightItem item(
            UUID checkId, UUID agentId, SkillDependency dependency, UUID toolId,
            SkillPreflightItemStatus status, ApiErrorCode errorCode, String message) {
        // 可选依赖出现问题时只告警，但仍保留稳定错误码，方便控制台给出可行动提示。
        ApiErrorCode code = status == SkillPreflightItemStatus.READY ? null : errorCode;
        return new SkillPreflightItem(checkId, agentId, dependency.logicalKey(), dependency.required(),
                toolId, status, code, message, code == null ? null : errorId());
    }

    private SkillPreflightStatus summarize(boolean enabled, List<SkillPreflightItem> items) {
        boolean failed = items.stream().anyMatch(item -> item.required()
                && item.status() != SkillPreflightItemStatus.READY);
        if (failed) {
            return SkillPreflightStatus.FAILED;
        }
        boolean warned = items.stream().anyMatch(item -> item.status() != SkillPreflightItemStatus.READY);
        return warned ? SkillPreflightStatus.PASSED_WITH_WARNINGS : SkillPreflightStatus.PASSED;
    }

    private SkillAccessException notFound() {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND,
                "技能或关联资源不存在", errorId(), false);
    }

    private static String errorId() {
        return UUID.randomUUID().toString();
    }
}
