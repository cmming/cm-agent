package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightItemStatus;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.domain.SkillBindingMode;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 维护技能逻辑依赖到租户工具的映射，并在技能启用时校验受影响绑定。
 *
 * <p>映射写入与严格审计处于同一工作单元：审计失败时 memory 暂存状态不发布、JDBC 事务回滚。
 * 启用状态下保存映射前，会按实际绑定策略解析受影响版本和 Agent，任何必需依赖在目标
 * Agent 上不可用都会拒绝本次修改。</p>
 */
@Service
public class SkillDependencyMappingService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillDependencyRepository dependencies;
    private final SkillDependencyMappingRepository mappings;
    private final AgentSkillBindingRepository bindings;
    private final ToolDefinitionRepository tools;
    private final SkillPreflightService preflights;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;

    /** 创建依赖映射服务并固定事务、预检和审计依赖。 */
    @Autowired
    public SkillDependencyMappingService(
            SkillDefinitionRepository definitions,
            SkillVersionRepository versions,
            SkillDependencyRepository dependencies,
            SkillDependencyMappingRepository mappings,
            AgentSkillBindingRepository bindings,
            ToolDefinitionRepository tools,
            SkillPreflightService preflights,
            SkillUnitOfWork workUnit,
            AuditAppender audit,
            SkillProperties properties) {
        this(definitions, versions, dependencies, mappings, bindings, tools, preflights,
                workUnit, audit, properties, Clock.systemUTC());
    }

    SkillDependencyMappingService(
            SkillDefinitionRepository definitions,
            SkillVersionRepository versions,
            SkillDependencyRepository dependencies,
            SkillDependencyMappingRepository mappings,
            AgentSkillBindingRepository bindings,
            ToolDefinitionRepository tools,
            SkillPreflightService preflights,
            SkillUnitOfWork workUnit,
            AuditAppender audit,
            SkillProperties properties,
            Clock clock) {
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.versions = Objects.requireNonNull(versions, "versions 不能为空");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies 不能为空");
        this.mappings = Objects.requireNonNull(mappings, "mappings 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.tools = Objects.requireNonNull(tools, "tools 不能为空");
        this.preflights = Objects.requireNonNull(preflights, "preflights 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 条件更新一个逻辑依赖映射，并把技能级修订单调加一。
     *
     * <p>目标工具必须属于当前可信租户，不能使用请求体中的租户覆盖。技能启用时先按实际
     * 绑定策略解析受影响版本与 Agent，任何必需依赖未通过检查都会以冲突失败并整体回滚。</p>
     *
     * @param principal 当前认证主体
     * @param skillId 目标技能标识
     * @param logicalKey 技能包声明的逻辑依赖键
     * @param toolId 目标租户工具标识
     * @param expectedRevision 调用方看到的技能级映射修订
     * @return 保存后的映射
     * @throws SkillAccessException 特性关闭、修订冲突、工具不可用或依赖检查失败时抛出
     */
    public SkillDependencyMapping map(
            PrincipalRef principal, UUID skillId, String logicalKey, UUID toolId, long expectedRevision) {
        requireFeatureEnabled();
        Objects.requireNonNull(logicalKey, "logicalKey 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        return workUnit.execute(() -> {
            SkillDefinition current = lockSkill(principal.tenantId(), skillId);
            if (current.dependencyMappingRevision() != expectedRevision) {
                throw stale("依赖映射已被其他请求修改，请刷新后重试");
            }
            requireDeclaredLogicalKey(principal.tenantId(), current, logicalKey);
            if (tools.findByTenantAndId(principal.tenantId(), toolId).isEmpty()) {
                throw new SkillAccessException(ApiErrorCode.SKILL_DEPENDENCY_UNAVAILABLE,
                        "目标工具不存在或不属于当前租户", errorId(), false);
            }
            Instant now = clock.instant();
            if (current.enabled()) {
                // 启用中的技能必须先在候选新映射下验证所有受影响的正式绑定，
                // 确保被拒绝的写入不会以暂存形式改变本工作单元内的映射集合。
                requireAffectedBindingsPass(principal, current, logicalKey, toolId);
            }
            SkillDependencyMapping mapping = new SkillDependencyMapping(
                    principal.tenantId(), skillId, logicalKey, toolId, principal.principalId(), now);
            mappings.save(mapping);
            SkillDefinition next = new SkillDefinition(
                    current.id(), current.tenantId(), current.name(),
                    current.candidateVersionId(), current.publishedVersionId(),
                    current.enabled(), current.accessEpoch(), Math.addExact(expectedRevision, 1),
                    current.createdBy(), principal.principalId(), current.createdAt(), now);
            if (!definitions.updateDependencyMappingRevision(next, expectedRevision)) {
                throw stale("依赖映射已被其他请求修改，请刷新后重试");
            }
            appendAudit(principal, "SKILL_DEPENDENCY_MAPPING_UPDATE", skillId,
                    "依赖映射已更新 " + logicalKey);
            return mapping;
        });
    }

    private void requireDeclaredLogicalKey(UUID tenantId, SkillDefinition definition, String logicalKey) {
        // 只允许映射技能版本真实声明过的逻辑键，避免写入永远无法使用的孤儿映射。
        Set<String> declared = new LinkedHashSet<>();
        Set<UUID> versionIds = new LinkedHashSet<>();
        if (definition.candidateVersionId() != null) {
            versionIds.add(definition.candidateVersionId());
        }
        if (definition.publishedVersionId() != null) {
            versionIds.add(definition.publishedVersionId());
        }
        for (UUID versionId : versionIds) {
            if (versionId == null) {
                continue;
            }
            dependencies.list(tenantId, definition.id(), versionId)
                    .stream().map(SkillDependency::logicalKey).forEach(declared::add);
        }
        if (!declared.contains(logicalKey)) {
            throw new SkillAccessException(ApiErrorCode.SKILL_DEPENDENCY_INVALID,
                    "技能版本未声明该逻辑依赖", errorId(), false);
        }
    }

    /**
     * 校验所有受影响绑定在当前映射下仍满足必需依赖。
     *
     * <p>跟随型绑定使用当前发布版本，固定型绑定使用各自固定版本；停用技能允许先调整映射，
     * 重新启用前再由启用流程执行完整预检。</p>
     */
    private void requireAffectedBindingsPass(
            PrincipalRef principal, SkillDefinition definition, String logicalKey, UUID toolId) {
        List<AgentSkillBinding> affected = bindings.listBySkill(principal.tenantId(), definition.id());
        for (AgentSkillBinding binding : affected) {
            UUID versionId = binding.mode() == SkillBindingMode.PINNED
                    ? binding.pinnedVersionId() : definition.publishedVersionId();
            if (versionId == null) {
                continue;
            }
            if (!declaresKey(principal.tenantId(), definition.id(), versionId, logicalKey)) {
                continue;
            }
            SkillPreflightResult result = preflightInTransaction(
                    principal, definition.id(), versionId, SkillPreflightScope.MAPPING_CHANGE, binding.agentId());
            if (result.check().status() == SkillPreflightStatus.FAILED) {
                String reason = result.items().stream()
                        .filter(item -> item.status() != SkillPreflightItemStatus.READY)
                        .map(SkillPreflightItem::message)
                        .findFirst().orElse("受影响 Agent 的必需依赖未就绪");
                throw new SkillAccessException(ApiErrorCode.SKILL_DEPENDENCY_UNAVAILABLE,
                        "映射更新被拒绝：" + reason, errorId(), false);
            }
            if (result.items().stream().anyMatch(item -> item.status() != SkillPreflightItemStatus.READY)) {
                throw new SkillAccessException(ApiErrorCode.SKILL_DEPENDENCY_UNAVAILABLE,
                        "映射更新会使受影响绑定出现未就绪依赖", errorId(), false);
            }
        }
    }

    /**
     * 在内层工作单元中执行预检，让预检证据与映射修改共享最外层事务边界。
     *
     * <p>memory 实现复用当前暂存副本，JDBC 实现复用当前事务；投影结果不单独发布。</p>
     */
    private SkillPreflightResult preflightInTransaction(
            PrincipalRef principal, UUID skillId, UUID versionId,
            SkillPreflightScope scope, UUID agentId) {
        return workUnit.execute(() -> preflights.preflight(principal, skillId, versionId, scope, agentId));
    }

    private boolean declaresKey(UUID tenantId, UUID skillId, UUID versionId, String logicalKey) {
        return dependencies.list(tenantId, skillId, versionId).stream()
                .anyMatch(dependency -> dependency.logicalKey().equals(logicalKey));
    }

    private SkillDefinition lockSkill(UUID tenantId, UUID skillId) {
        mappings.lockSkill(tenantId, skillId);
        try {
            return definitions.lock(tenantId, skillId);
        } catch (NoSuchElementException ex) {
            throw new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND,
                    "技能不存在", errorId(), false);
        }
    }

    private void appendAudit(PrincipalRef principal, String eventType, UUID resourceId, String message) {
        audit.append(principal.tenantId(), principal.principalId(), eventType,
                "SKILL", resourceId.toString(), "SUCCEEDED", message);
    }

    private void requireFeatureEnabled() {
        if (!properties.isEnabled()) {
            throw new SkillAccessException(ApiErrorCode.SKILL_FEATURE_DISABLED,
                    "技能功能未启用", errorId(), false);
        }
    }

    private static SkillAccessException stale(String message) {
        return new SkillAccessException(ApiErrorCode.SKILL_PREFLIGHT_STALE, message, errorId(), false);
    }

    private static String errorId() {
        return UUID.randomUUID().toString();
    }

}
