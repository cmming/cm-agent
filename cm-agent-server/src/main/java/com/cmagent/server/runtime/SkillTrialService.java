package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.RunKind;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillTrialRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SkillPreflightResult;
import com.cmagent.server.service.SkillPreflightService;
import com.cmagent.server.service.SkillUnitOfWork;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 编排指定技能版本的真实 TEST Run，并将实际读取事实转换为可发布性结论。
 *
 * <p>试运行不会创建会话、消息或正式绑定，但模型和工具仍使用与正式运行相同的治理链路，可能产生
 * 真实工具副作用。数据库工作单元仅用于创建和收口事实，绝不包围模型或工具网络调用。</p>
 */
@Service
public class SkillTrialService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillTrialRepository trials;
    private final SkillLoadRecordRepository loads;
    private final SkillPreflightService preflights;
    private final RunPersistenceService runs;
    private final RunExecutionService execution;
    private final ToolApprovalService approvals;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;

    /** 创建试运行服务并固定其可信治理依赖。 */
    @Autowired
    public SkillTrialService(
            SkillDefinitionRepository definitions,
            SkillVersionRepository versions,
            SkillTrialRepository trials,
            SkillLoadRecordRepository loads,
            SkillPreflightService preflights,
            RunPersistenceService runs,
            RunExecutionService execution,
            ToolApprovalService approvals,
            SkillUnitOfWork workUnit,
            AuditAppender audit,
            SkillProperties properties
    ) {
        this(definitions, versions, trials, loads, preflights, runs, execution, approvals, workUnit, audit,
                properties, Clock.systemUTC());
    }

    SkillTrialService(
            SkillDefinitionRepository definitions,
            SkillVersionRepository versions,
            SkillTrialRepository trials,
            SkillLoadRecordRepository loads,
            SkillPreflightService preflights,
            RunPersistenceService runs,
            RunExecutionService execution,
            ToolApprovalService approvals,
            SkillUnitOfWork workUnit,
            AuditAppender audit,
            SkillProperties properties,
            Clock clock
    ) {
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.versions = Objects.requireNonNull(versions, "versions 不能为空");
        this.trials = Objects.requireNonNull(trials, "trials 不能为空");
        this.loads = Objects.requireNonNull(loads, "loads 不能为空");
        this.preflights = Objects.requireNonNull(preflights, "preflights 不能为空");
        this.runs = Objects.requireNonNull(runs, "runs 不能为空");
        this.execution = Objects.requireNonNull(execution, "execution 不能为空");
        this.approvals = Objects.requireNonNull(approvals, "approvals 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 创建已预检的 TEST Run 及其试运行事实，但不在数据库事务内执行模型。
     *
     * @param principal 当前可信认证主体
     * @param skillId 待验证技能
     * @param versionId 待临时注入的不可变版本
     * @param agentId 执行真实运行的目标 Agent
     * @param input 测试输入
     * @return 状态为 RUNNING 的试运行事实
     */
    public SkillTrial start(PrincipalRef principal, UUID skillId, UUID versionId, UUID agentId, String input) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        requireFeatureEnabled();
        SkillDefinition definition = definitions.find(principal.tenantId(), skillId).orElseThrow(this::notFound);
        versions.find(principal.tenantId(), skillId, versionId).orElseThrow(this::notFound);
        SkillPreflightResult preflight = preflights.preflight(
                principal, skillId, versionId, com.cmagent.core.domain.SkillPreflightScope.TRIAL, agentId);
        if (!preflight.passing()) {
            throw failure(ApiErrorCode.SKILL_RELEASE_FAILED, "试运行预检未通过，请先修复必需依赖");
        }
        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        return workUnit.execute(() -> {
            // 预检和锁定后的定义必须仍采用同一映射修订，避免映射刚变更时沿用旧结论。
            SkillDefinition current = definitions.lock(principal.tenantId(), skillId);
            if (current.dependencyMappingRevision() != preflight.check().mappingRevision()) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "依赖映射已变化，请重新预检后试运行");
            }
            versions.find(principal.tenantId(), skillId, versionId).orElseThrow(this::notFound);
            RunRecord run = runs.start(principal, agentId, input, runId, RunKind.TEST);
            SkillTrial trial = new SkillTrial(run.id(), principal.tenantId(), skillId, versionId, agentId,
                    current.dependencyMappingRevision(), SkillTrialStatus.RUNNING, false,
                    principal.principalId(), now, now);
            SkillTrial saved = trials.insert(trial);
            audit.append(principal.tenantId(), principal.principalId(), "SKILL_TRIAL_CREATE", "SKILL",
                    skillId.toString(), "RUNNING", "已创建指定版本技能试运行");
            return saved;
        });
    }

    /** 使用与正式运行相同的 Runtime、工具治理和脱敏边界执行已创建试运行。 */
    public AgentRuntimeResult execute(
            PrincipalRef principal,
            SkillTrial trial,
            String input,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        requireOwner(principal, trial);
        RunRecord running = runs.findDetail(principal.tenantId(), trial.agentId(), trial.runId()).run();
        if (running.kind() != RunKind.TEST || !running.status().isActive()) {
            throw failure(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE, "试运行已结束或不可用");
        }
        try {
            AgentRuntimeResult result = execution.runTrialPrepared(principal, trial.agentId(), running, input,
                    new SkillRuntimeSelection(trial.skillId(), trial.versionId(), trial.runId(), trial.mappingRevision()),
                    deltaConsumer, progressConsumer);
            if (result.run().status() == RunStatus.WAITING_APPROVAL) {
                try {
                    approvals.createForRun(principal, trial.agentId(), trial.runId(), result.pendingApproval());
                } catch (RuntimeException approvalFailure) {
                    // Run 已进入等待状态但审批事实未落库时，必须关闭状态槽，避免 HIGH 工具被后续误恢复。
                    approvals.failRunAfterApprovalCreationFailure(principal, trial.agentId(), trial.runId());
                    throw approvalFailure;
                }
            }
            finalizeResult(principal, trial.runId(), result.run());
            return result;
        } catch (RuntimeException exception) {
            markFailed(principal, trial.runId());
            throw exception;
        }
    }

    /** 依据同一 TEST Run 的真实目标版本读取记录收口试运行状态。 */
    public SkillTrial finalizeResult(PrincipalRef principal, UUID runId, AgentRunResult result) {
        SkillTrial current = trials.find(principal.tenantId(), runId).orElseThrow(this::notFound);
        requireOwner(principal, current);
        SkillTrialStatus target = result.status() == RunStatus.WAITING_APPROVAL
                ? SkillTrialStatus.WAITING_APPROVAL
                : result.status() != RunStatus.SUCCEEDED
                ? SkillTrialStatus.FAILED
                : targetWasLoaded(principal, current) ? SkillTrialStatus.PASSED : SkillTrialStatus.NOT_TRIGGERED;
        boolean qualifies = target == SkillTrialStatus.PASSED;
        return update(principal, current, target, qualifies);
    }

    /** 读取试运行状态时始终以当前可信租户和 Run 标识作为查询边界。 */
    public SkillTrial get(PrincipalRef principal, UUID runId) {
        SkillTrial trial = trials.find(principal.tenantId(), runId).orElseThrow(this::notFound);
        requireOwner(principal, trial);
        return trial;
    }

    /**
     * 查询当前主体拥有的 TEST Run 结果明细。
     *
     * <p>复用运行持久化服务的租户、Agent 查询边界和脱敏映射；返回前再次确认运行类型，
     * 使试运行预览不会意外暴露同一 Agent 下的正式 Run。</p>
     *
     * @param principal 当前可信认证主体
     * @param runId 目标试运行标识
     * @return 已脱敏的运行记录及工具调用摘要，不含未过滤的输入或主体上下文
     * @throws SkillAccessException 试运行不属于当前租户或主体时按不存在处理
     * @throws ResponseStatusException 关联运行不存在时按不存在处理
     */
    public RunPersistenceService.RunDetail runDetail(PrincipalRef principal, UUID runId) {
        SkillTrial trial = get(principal, runId);
        RunPersistenceService.RunDetail detail = runs.findDetail(principal.tenantId(), trial.agentId(), runId);
        if (detail.run().kind() != RunKind.TEST) {
            throw notFound();
        }
        return detail;
    }

    private boolean targetWasLoaded(PrincipalRef principal, SkillTrial trial) {
        return loads.listForBudget(principal.tenantId(), trial.runId()).stream().anyMatch(load ->
                load.status() == SkillLoadStatus.SUCCEEDED
                        && trial.skillId().equals(load.skillId())
                        && trial.versionId().equals(load.versionId()));
    }

    /**
     * 依据最新试运行事实将仍活动的试运行收口为失败。
     *
     * <p>模型运行与审批创建不在同一数据库事务中；因此不能使用调用开始时的旧状态进行 CAS，
     * 否则审批创建失败会因状态已变为 {@code WAITING_APPROVAL} 而留下孤儿试运行。</p>
     */
    public void markFailed(PrincipalRef principal, UUID runId) {
        SkillTrial current = trials.find(principal.tenantId(), runId).orElse(null);
        if (current == null || (current.status() != SkillTrialStatus.RUNNING
                && current.status() != SkillTrialStatus.WAITING_APPROVAL)) {
            return;
        }
        try {
            update(principal, current, SkillTrialStatus.FAILED, false);
        } catch (SkillAccessException conflict) {
            if (conflict.code() != ApiErrorCode.SKILL_CANDIDATE_CONFLICT) {
                throw conflict;
            }
        }
    }

    /** 仅当功能已显式启用时允许创建会产生真实模型和工具副作用的试运行。 */
    private void requireFeatureEnabled() {
        if (!properties.isEnabled()) {
            throw failure(ApiErrorCode.SKILL_FEATURE_DISABLED, "技能功能已关闭，不能创建试运行");
        }
    }

    private SkillTrial update(PrincipalRef principal, SkillTrial current, SkillTrialStatus status, boolean qualifies) {
        Instant now = clock.instant();
        SkillTrial next = new SkillTrial(current.runId(), current.tenantId(), current.skillId(), current.versionId(),
                current.agentId(), current.mappingRevision(), status, qualifies, current.createdBy(),
                current.createdAt(), now);
        return workUnit.execute(() -> {
            if (!trials.update(next, current.status())) {
                throw failure(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "试运行状态已变化，请刷新后查看最新结果");
            }
            audit.append(principal.tenantId(), principal.principalId(), "SKILL_TRIAL_COMPLETE", "SKILL",
                    current.skillId().toString(), status.name(), "指定版本技能试运行状态已更新");
            return next;
        });
    }

    private void requireOwner(PrincipalRef principal, SkillTrial trial) {
        if (!principal.tenantId().equals(trial.tenantId()) || !principal.principalId().equals(trial.createdBy())) {
            throw notFound();
        }
    }

    private SkillAccessException notFound() {
        return failure(ApiErrorCode.SKILL_NOT_FOUND, "技能试运行或关联资源不存在");
    }

    private static SkillAccessException failure(ApiErrorCode code, String message) {
        return new SkillAccessException(code, message, UUID.randomUUID().toString(), false);
    }
}
