package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillBindingMode;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.domain.SkillReleaseAction;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillReleaseRepository;
import com.cmagent.core.repository.SkillTrialRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditAppender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理技能候选发布、历史回滚和 Agent 取版策略。
 *
 * <p>所有写操作都在 {@link SkillUnitOfWork} 内重读并锁定技能定义。这样浏览器携带的版本、
 * 修订或试运行标识只能作为乐观并发条件，不能绕过当前租户的预检、发布历史和审计边界。</p>
 */
@Service
public class SkillReleaseService {
    private final SkillDefinitionRepository definitions;
    private final SkillReleaseRepository releases;
    private final SkillTrialRepository trials;
    private final AgentDefinitionRepository agents;
    private final AgentSkillBindingRepository bindings;
    private final SkillPreflightService preflights;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final Clock clock;

    /** 创建发布服务并固定所有治理事实的可信来源。 */
    @Autowired
    public SkillReleaseService(
            SkillDefinitionRepository definitions,
            SkillReleaseRepository releases,
            SkillTrialRepository trials,
            AgentDefinitionRepository agents,
            AgentSkillBindingRepository bindings,
            SkillPreflightService preflights,
            SkillUnitOfWork workUnit,
            AuditAppender audit
    ) {
        this(definitions, releases, trials, agents, bindings, preflights, workUnit, audit, Clock.systemUTC());
    }

    SkillReleaseService(
            SkillDefinitionRepository definitions,
            SkillReleaseRepository releases,
            SkillTrialRepository trials,
            AgentDefinitionRepository agents,
            AgentSkillBindingRepository bindings,
            SkillPreflightService preflights,
            SkillUnitOfWork workUnit,
            AuditAppender audit,
            Clock clock
    ) {
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.releases = Objects.requireNonNull(releases, "releases 不能为空");
        this.trials = Objects.requireNonNull(trials, "trials 不能为空");
        this.agents = Objects.requireNonNull(agents, "agents 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.preflights = Objects.requireNonNull(preflights, "preflights 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 将当前候选正式发布；试运行和即时预检必须仍对应锁定后的候选与映射修订。
     *
     * @param principal 当前认证主体，提供可信租户和审计主体
     * @param skillId 技能稳定标识
     * @param candidateVersionId 调用方看到的候选版本
     * @param expectedPublishedVersionId 调用方看到的已发布版本；首次发布可为空
     * @param qualifyingTrialRunId 已实际读取目标版本的成功试运行
     * @return 新增的不可变发布事实
     */
    public SkillRelease publish(PrincipalRef principal, UUID skillId, UUID candidateVersionId,
                                UUID expectedPublishedVersionId, UUID qualifyingTrialRunId) {
        return workUnit.execute(() -> {
            SkillDefinition current = lock(principal, skillId);
            requirePointers(current, candidateVersionId, expectedPublishedVersionId);
            SkillTrial trial = trials.find(principal.tenantId(), qualifyingTrialRunId)
                    .orElseThrow(() -> failure(ApiErrorCode.SKILL_TRIAL_REQUIRED, "找不到候选版本的合格试运行"));
            if (!trial.skillId().equals(skillId) || !trial.versionId().equals(candidateVersionId)
                    || trial.mappingRevision() != current.dependencyMappingRevision()
                    || trial.status() != SkillTrialStatus.PASSED || !trial.qualifiesRelease()) {
                throw failure(ApiErrorCode.SKILL_TRIAL_REQUIRED, "候选版本缺少当前映射修订下的合格试运行");
            }
            SkillPreflightResult check = preflights.preflight(principal, skillId, candidateVersionId,
                    SkillPreflightScope.PUBLISH, null);
            if (!check.passing()) {
                throw failure(ApiErrorCode.SKILL_RELEASE_FAILED, "发布预检未通过，请先修复必需依赖");
            }
            SkillRelease release = nextRelease(principal, current, SkillReleaseAction.PUBLISH,
                    current.publishedVersionId(), candidateVersionId, check.check().id(), qualifyingTrialRunId);
            releases.insert(release);
            SkillDefinition next = new SkillDefinition(current.id(), current.tenantId(), current.name(),
                    null, candidateVersionId, current.enabled(), current.accessEpoch(),
                    current.dependencyMappingRevision(), current.createdBy(), principal.principalId(),
                    current.createdAt(), clock.instant());
            if (!definitions.updatePointers(next, candidateVersionId, expectedPublishedVersionId)) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "技能版本已变化，请刷新后重试");
            }
            appendAudit(principal, "SKILL_PUBLISH", skillId, "候选版本已正式发布");
            return release;
        });
    }

    /**
     * 将曾发布的历史版本重新发布为当前版本；回滚不复用试运行，但必须重新做即时预检。
     */
    public SkillRelease rollback(PrincipalRef principal, UUID skillId, UUID targetVersionId,
                                 UUID expectedPublishedVersionId) {
        return workUnit.execute(() -> {
            SkillDefinition current = lock(principal, skillId);
            if (!Objects.equals(current.publishedVersionId(), expectedPublishedVersionId)) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "已发布版本已变化，请刷新后重试");
            }
            boolean released = releases.list(principal.tenantId(), skillId).stream()
                    .anyMatch(item -> item.versionId().equals(targetVersionId));
            if (!released) {
                throw failure(ApiErrorCode.SKILL_VERSION_NOT_RELEASED, "只能回滚到曾正式发布的历史版本");
            }
            SkillPreflightResult check = preflights.preflight(principal, skillId, targetVersionId,
                    SkillPreflightScope.PUBLISH, null);
            if (!check.passing()) {
                throw failure(ApiErrorCode.SKILL_RELEASE_FAILED, "回滚目标的发布预检未通过");
            }
            SkillRelease release = nextRelease(principal, current, SkillReleaseAction.ROLLBACK,
                    current.publishedVersionId(), targetVersionId, check.check().id(), null);
            releases.insert(release);
            SkillDefinition next = new SkillDefinition(current.id(), current.tenantId(), current.name(),
                    current.candidateVersionId(), targetVersionId, current.enabled(), current.accessEpoch(),
                    current.dependencyMappingRevision(), current.createdBy(), principal.principalId(),
                    current.createdAt(), clock.instant());
            if (!definitions.updatePointers(next, current.candidateVersionId(), expectedPublishedVersionId)) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "技能版本已变化，请刷新后重试");
            }
            appendAudit(principal, "SKILL_ROLLBACK", skillId, "技能已回滚到历史发布版本");
            return release;
        });
    }

    /** 更新已有绑定的取版策略，或在不存在时按指定策略创建绑定。 */
    public AgentSkillBinding bindOrUpdate(PrincipalRef principal, UUID agentId, UUID skillId,
                                          SkillBindingMode mode, UUID pinnedVersionId, Long expectedRevision) {
        return workUnit.execute(() -> {
            if (agents.findByTenantAndId(principal.tenantId(), agentId).isEmpty()) {
                throw failure(ApiErrorCode.SKILL_NOT_FOUND, "Agent 不存在或不属于当前租户");
            }
            bindings.lockAgent(principal.tenantId(), agentId);
            SkillDefinition skill = lock(principal, skillId);
            if (!skill.enabled()) {
                throw failure(ApiErrorCode.SKILL_ACCESS_REVOKED, "技能已停用，不能绑定或切换版本策略");
            }
            UUID resolvedVersionId = resolveVersion(principal, skill, mode, pinnedVersionId);
            SkillPreflightResult check = preflights.preflight(principal, skillId, resolvedVersionId,
                    SkillPreflightScope.BINDING, agentId);
            if (!check.passing()) {
                throw failure(ApiErrorCode.SKILL_RELEASE_FAILED, "目标 Agent 尚未满足技能必需依赖");
            }
            Instant now = clock.instant();
            AgentSkillBinding current = bindings.find(principal.tenantId(), agentId, skillId).orElse(null);
            if (current == null) {
                AgentSkillBinding created = new AgentSkillBinding(UUID.randomUUID(), principal.tenantId(), agentId,
                        skillId, mode, mode == SkillBindingMode.PINNED ? pinnedVersionId : null, 0,
                        principal.principalId(), principal.principalId(), now, now);
                bindings.insert(created);
                appendAudit(principal, "AGENT_SKILL_BIND", agentId, "Agent 已绑定技能并设置取版策略");
                return created;
            }
            if (expectedRevision == null || current.revision() != expectedRevision) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "技能绑定策略已变化，请刷新后重试");
            }
            AgentSkillBinding next = new AgentSkillBinding(current.id(), current.tenantId(), current.agentId(),
                    current.skillId(), mode, mode == SkillBindingMode.PINNED ? pinnedVersionId : null,
                    Math.addExact(current.revision(), 1), current.boundBy(), principal.principalId(),
                    current.createdAt(), now);
            if (!bindings.updateStrategy(next, current.revision())) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "技能绑定策略已变化，请刷新后重试");
            }
            appendAudit(principal, "AGENT_SKILL_BINDING_STRATEGY_UPDATE", agentId, "Agent 技能取版策略已更新");
            return next;
        });
    }

    private SkillDefinition lock(PrincipalRef principal, UUID skillId) {
        try {
            return definitions.lock(principal.tenantId(), skillId);
        } catch (NoSuchElementException exception) {
            // 只有缺失记录需要统一为不泄露跨租户存在性的业务错误；持久化故障必须继续抛出，
            // 否则调用方会把可诊断的数据库异常误判为“技能不存在”。
            throw failure(ApiErrorCode.SKILL_NOT_FOUND, "技能不存在或不属于当前租户");
        }
    }

    private void requirePointers(SkillDefinition current, UUID candidateVersionId, UUID expectedPublishedVersionId) {
        if (!Objects.equals(current.candidateVersionId(), candidateVersionId)
                || !Objects.equals(current.publishedVersionId(), expectedPublishedVersionId)) {
            throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "候选或已发布版本已变化，请刷新后重试");
        }
    }

    private UUID resolveVersion(PrincipalRef principal, SkillDefinition skill,
                                SkillBindingMode mode, UUID pinnedVersionId) {
        Objects.requireNonNull(mode, "mode 不能为空");
        if (mode == SkillBindingMode.FOLLOW_PUBLISHED) {
            if (skill.publishedVersionId() == null) {
                throw failure(ApiErrorCode.SKILL_NOT_PUBLISHED, "技能尚未正式发布，不能跟随绑定");
            }
            return skill.publishedVersionId();
        }
        if (pinnedVersionId == null || releases.list(principal.tenantId(), skill.id()).stream()
                .noneMatch(item -> item.versionId().equals(pinnedVersionId))) {
            throw failure(ApiErrorCode.SKILL_VERSION_NOT_RELEASED, "固定版本必须是曾正式发布的技能版本");
        }
        return pinnedVersionId;
    }

    private SkillRelease nextRelease(PrincipalRef principal, SkillDefinition skill, SkillReleaseAction action,
                                     UUID previousVersionId, UUID versionId, UUID preflightId, UUID trialRunId) {
        long nextNo = releases.lockLatest(principal.tenantId(), skill.id())
                .map(last -> Math.addExact(last.releaseNo(), 1)).orElse(1L);
        return new SkillRelease(UUID.randomUUID(), principal.tenantId(), skill.id(), nextNo, action,
                previousVersionId, versionId, preflightId, trialRunId, principal.principalId(), clock.instant());
    }

    private void appendAudit(PrincipalRef principal, String eventType, UUID resourceId, String message) {
        audit.append(principal.tenantId(), principal.principalId(), eventType,
                "SKILL", resourceId.toString(), "SUCCEEDED", message);
    }

    private static SkillAccessException failure(ApiErrorCode code, String message) {
        return new SkillAccessException(code, message, UUID.randomUUID().toString(), false);
    }
}
