package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillSnapshotRef;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.runtime.SkillReadResult;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SkillUnitOfWork;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在原生技能读取前后执行租户、Run、快照、撤销、预算、记录和严格审计检查。
 *
 * <p>正文只有在工作单元成功提交后才返回调用方。受控拒绝先保存记录再在事务外抛出，
 * 基础设施异常则直接回滚，避免产生“已交付”假记录。</p>
 */
@Service
public class GovernedSkillAccessService implements SkillAccessGateway {
    private final RunRepository runs;
    private final RunSkillSnapshotRepository snapshots;
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final AgentSkillBindingRepository bindings;
    private final SkillLoadRecordRepository records;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;

    /** 创建技能读取治理服务并固定全部安全和提交依赖。 */
    @Autowired
    public GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties) {
        this(runs, snapshots, definitions, versions, resources, bindings, records,
                workUnit, audit, properties, Clock.systemUTC());
    }

    GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs 不能为空");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots 不能为空");
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.versions = Objects.requireNonNull(versions, "versions 不能为空");
        this.resources = Objects.requireNonNull(resources, "resources 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.records = Objects.requireNonNull(records, "records 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 执行一次受治理读取；幂等重试复用已提交正文，不再次调用原生加载器或扣减预算。
     *
     * @param request 由运行时适配器从固定快照构造的可信请求
     * @param nativeLoader 只读取本次运行内存技能仓库的原生加载器
     * @return 已提交记录的读取结果
     */
    @Override
    public SkillReadResult load(SkillReadRequest request, Supplier<String> nativeLoader) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(nativeLoader, "nativeLoader 不能为空");
        ReadOutcome outcome = workUnit.execute(() -> loadInside(request, nativeLoader));
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.result();
    }

    private ReadOutcome loadInside(SkillReadRequest request, Supplier<String> nativeLoader) {
        RunRecord run = runs.findByTenantAndAgentAndId(
                        request.principal().tenantId(), request.agentId(), request.runId())
                .filter(candidate -> candidate.principalId().equals(request.principal().principalId()))
                .orElse(null);
        if (run == null) {
            return denied(request, ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                    "本轮运行或技能快照不可用", true, null, null);
        }
        RunSkillSnapshot snapshot;
        try {
            snapshot = snapshots.lock(request.principal().tenantId(), request.runId());
        } catch (NoSuchElementException missing) {
            return denied(request, ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                    "本轮技能快照不可用", true, null, null);
        }
        var existing = records.findByCall(request.principal().tenantId(), request.runId(), request.modelCallId());
        if (existing.isPresent()) {
            return replay(request, existing.get());
        }
        if (!properties.isEnabled()) {
            return denied(request, ApiErrorCode.SKILL_FEATURE_DISABLED,
                    "技能功能已关闭，请重新发起对话", true, request.skillId(), request.versionId());
        }
        ResolvedResource resolved = resolve(request, snapshot);
        if (resolved.failure() != null) {
            return persistFailure(request, resolved.failure(), SkillLoadStatus.DENIED,
                    resolved.skillId(), resolved.versionId(), safePath(request.path()), 0);
        }
        List<SkillLoadRecord> budget = records.listForBudget(request.principal().tenantId(), request.runId());
        int delivered = budget.stream().mapToInt(SkillLoadRecord::deliveredBytes).sum();
        if (budget.size() >= properties.getMaxLoadAttempts()
                || resolved.byteLength() > properties.getMaxLoadedBytes() - delivered) {
            return denied(request, ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED,
                    "本轮技能读取已达上限", false, request.skillId(), request.versionId());
        }

        Instant started = clock.instant();
        String loaded;
        try {
            loaded = Objects.requireNonNull(nativeLoader.get(), "原生技能读取结果不能为空");
        } catch (RuntimeException failure) {
            SkillAccessException controlled = new SkillAccessException(ApiErrorCode.SKILL_LOAD_FAILED,
                    "技能内容读取失败", request.attemptId().toString(), true);
            return persistFailure(request, controlled, SkillLoadStatus.FAILED,
                    request.skillId(), request.versionId(), safePath(request.path()), elapsed(started));
        }

        // 原生调用之后仍在同一短工作单元内复核撤销凭据，提交记录和严格审计后才允许正文离开服务。
        ResolvedResource finalState = resolve(request, snapshot);
        if (finalState.failure() != null) {
            return persistFailure(request, finalState.failure(), SkillLoadStatus.DENIED,
                    request.skillId(), request.versionId(), safePath(request.path()), elapsed(started));
        }
        SkillLoadRecord record = new SkillLoadRecord(UUID.randomUUID(), request.principal().tenantId(),
                request.runId(), request.modelCallId(), budget.size() + 1, request.skillId(), request.versionId(),
                request.path(), SkillLoadStatus.SUCCEEDED, resolved.byteLength(), elapsed(started),
                null, null, clock.instant());
        records.insert(record);
        appendAudit(request, "SUCCEEDED", "技能内容读取成功");
        return ReadOutcome.success(new SkillReadResult(loaded, record.id()));
    }

    private ReadOutcome replay(SkillReadRequest request, SkillLoadRecord existing) {
        boolean same = Objects.equals(existing.skillId(), request.skillId())
                && Objects.equals(existing.versionId(), request.versionId())
                && Objects.equals(existing.path(), request.path());
        if (!same) {
            throw new SkillAccessException(ApiErrorCode.SKILL_CONFLICT,
                    "模型调用标识已用于其他技能读取", request.attemptId().toString(), true);
        }
        if (existing.status() != SkillLoadStatus.SUCCEEDED) {
            throw new SkillAccessException(existing.errorCode(), "技能读取重试仍被拒绝",
                    existing.errorId(), existing.errorCode() != ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED);
        }
        ResolvedResource resource = resolve(request, snapshots.lock(
                request.principal().tenantId(), request.runId()));
        if (resource.failure() != null) {
            throw resource.failure();
        }
        return ReadOutcome.success(new SkillReadResult(resource.content(), existing.id()));
    }

    private ResolvedResource resolve(SkillReadRequest request, RunSkillSnapshot snapshot) {
        if (request.skillId() == null || request.versionId() == null || request.path() == null) {
            return ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                    ApiErrorCode.SKILL_NOT_FOUND, "请求的技能资源不存在", request, false));
        }
        SkillSnapshotRef reference = snapshot.skills().stream()
                .filter(item -> item.skillId().equals(request.skillId())
                        && item.versionId().equals(request.versionId()))
                .findFirst().orElse(null);
        if (reference == null) {
            return ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                    ApiErrorCode.SKILL_ACCESS_REVOKED, "本轮无权读取该技能", request, true));
        }
        SkillDefinition definition;
        try {
            definition = definitions.lock(request.principal().tenantId(), request.skillId());
            bindings.lockAgent(request.principal().tenantId(), request.agentId());
        } catch (NoSuchElementException missing) {
            return ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                    ApiErrorCode.SKILL_ACCESS_REVOKED, "本轮使用的技能已停用或解绑", request, true));
        }
        AgentSkillBinding binding = bindings.find(
                request.principal().tenantId(), request.agentId(), request.skillId()).orElse(null);
        if (!definition.enabled() || definition.accessEpoch() != reference.accessEpoch()
                || binding == null || !binding.id().equals(reference.bindingId())) {
            return ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                    ApiErrorCode.SKILL_ACCESS_REVOKED, "本轮使用的技能已停用或解绑", request, true));
        }
        SkillVersion version = versions.find(request.principal().tenantId(), request.skillId(), request.versionId())
                .orElse(null);
        if (version == null) {
            return ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                    ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE, "本轮固定技能版本不可用", request, true));
        }
        if ("SKILL.md".equals(request.path())) {
            String content = version.content();
            return ResolvedResource.success(content, content.getBytes(StandardCharsets.UTF_8).length);
        }
        SkillResource resource = resources.list(
                        request.principal().tenantId(), request.skillId(), request.versionId()).stream()
                .filter(item -> item.path().equals(request.path())).findFirst().orElse(null);
        return resource == null
                ? ResolvedResource.denied(request.skillId(), request.versionId(), failure(
                        ApiErrorCode.SKILL_NOT_FOUND, "请求的技能资源不存在", request, false))
                : ResolvedResource.success(resource.content(), resource.byteLength());
    }

    private ReadOutcome denied(SkillReadRequest request, ApiErrorCode code, String message,
                               boolean fatal, UUID skillId, UUID versionId) {
        return persistFailure(request, new SkillAccessException(code, message,
                        request.attemptId().toString(), fatal), SkillLoadStatus.DENIED,
                skillId, versionId, safePath(request.path()), 0);
    }

    private ReadOutcome persistFailure(SkillReadRequest request, SkillAccessException failure,
                                       SkillLoadStatus status, UUID skillId, UUID versionId,
                                       String path, long durationMillis) {
        int attemptNo = records.listForBudget(request.principal().tenantId(), request.runId()).size() + 1;
        SkillLoadRecord record = new SkillLoadRecord(UUID.randomUUID(), request.principal().tenantId(),
                request.runId(), request.modelCallId(), attemptNo, skillId, versionId, path,
                status, 0, durationMillis, failure.code(), failure.errorId(), clock.instant());
        records.insert(record);
        appendAudit(request, status.name(), failure.safeMessage());
        return ReadOutcome.denied(failure);
    }

    private void appendAudit(SkillReadRequest request, String status, String message) {
        audit.append(request.principal().tenantId(), request.principal().principalId(),
                "SKILL_LOAD", "RUN", request.runId().toString(), status, message);
    }

    private long elapsed(Instant started) {
        return Math.max(0, Duration.between(started, clock.instant()).toMillis());
    }

    private static String safePath(String path) {
        if (path == null || path.length() > 240 || path.contains("..") || path.indexOf('\0') >= 0) {
            return null;
        }
        return path;
    }

    private static SkillAccessException failure(
            ApiErrorCode code, String message, SkillReadRequest request, boolean fatal) {
        return new SkillAccessException(code, message, request.attemptId().toString(), fatal);
    }

    private record ReadOutcome(SkillReadResult result, SkillAccessException failure) {
        private static ReadOutcome success(SkillReadResult result) {
            return new ReadOutcome(result, null);
        }

        private static ReadOutcome denied(SkillAccessException failure) {
            return new ReadOutcome(null, failure);
        }
    }

    private record ResolvedResource(String content, int byteLength, UUID skillId,
                                    UUID versionId, SkillAccessException failure) {
        private static ResolvedResource success(String content, int byteLength) {
            return new ResolvedResource(content, byteLength, null, null, null);
        }

        private static ResolvedResource denied(
                UUID skillId, UUID versionId, SkillAccessException failure) {
            return new ResolvedResource(null, 0, skillId, versionId, failure);
        }
    }
}
