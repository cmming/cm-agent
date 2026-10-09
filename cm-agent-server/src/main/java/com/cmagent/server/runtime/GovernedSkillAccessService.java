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
import com.cmagent.core.domain.SkillSnapshotOrigin;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillTrialRepository;
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
    private final SkillTrialRepository trials;
    private final SkillLoadRecordRepository records;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;
    /** 服务级复用沙箱并发门控，执行期间不持有数据库事务。 */
    private final com.cmagent.core.runtime.SkillSandboxBackend sandbox;
    /** Spring 装配完成后固定产物编排；旧嵌入构造器保留纯文本行为。 */
    private SkillArtifactService artifacts;
    /** 生命周期装配在首次调用前完成，不在执行中切换服务。 */
    @Autowired
    void configureArtifacts(SkillArtifactService artifacts) { this.artifacts=artifacts; }
    /** 只记录可信上下文与脱敏异常，不记录脚本、输入或输出正文。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GovernedSkillAccessService.class);

    /** 创建技能读取治理服务并固定全部安全和提交依赖。 */
    public GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillTrialRepository trials,
            SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties) {
        this(runs, snapshots, definitions, versions, resources, bindings, trials, records,
                workUnit, audit, properties, Clock.systemUTC());
    }

    /** Spring注入可替换执行接口；保留旧构造器仅兼容嵌入和R0单元测试。 */
    @Autowired
    public GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillTrialRepository trials, SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("managedSkillSandbox")
            com.cmagent.core.runtime.SkillSandboxBackend sandbox) {
        this(runs,snapshots,definitions,versions,resources,bindings,trials,records,workUnit,audit,properties,Clock.systemUTC(),sandbox);
    }

    GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillTrialRepository trials,
            SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties, Clock clock) {
        this(runs, snapshots, definitions, versions, resources, bindings, trials, records,
                workUnit, audit, properties, clock, new DockerSkillSandbox(properties.getSandbox()));
    }

    GovernedSkillAccessService(
            RunRepository runs, RunSkillSnapshotRepository snapshots,
            SkillDefinitionRepository definitions, SkillVersionRepository versions,
            SkillResourceRepository resources, AgentSkillBindingRepository bindings,
            SkillTrialRepository trials, SkillLoadRecordRepository records, SkillUnitOfWork workUnit,
            AuditAppender audit, SkillProperties properties, Clock clock, com.cmagent.core.runtime.SkillSandboxBackend sandbox) {
        this.runs = Objects.requireNonNull(runs, "runs 不能为空");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots 不能为空");
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.versions = Objects.requireNonNull(versions, "versions 不能为空");
        this.resources = Objects.requireNonNull(resources, "resources 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.trials = Objects.requireNonNull(trials, "trials 不能为空");
        this.records = Objects.requireNonNull(records, "records 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    }

    /** @return 技能和沙箱开关同时开启时才允许适配器公开执行工具 */
    @Override
    public boolean executionEnabled() {
        return properties.isEnabled() && properties.getSandbox().isEnabled();
    }

    /**
     * 准备、执行并收口一次固定脚本调用；同一调用标识拒绝重放，不重新产生副作用。
     *
     * <p>读取记录表示资源已交给沙箱准备阶段，并共用 Run 读取预算；它不表示脚本执行成功。
     * 执行终态单独通过 SKILL_EXECUTE 审计记录。数据库短工作单元在容器运行前已提交，
     * 后置复核发现停用、解绑或 Run 结束时丢弃输出；容器内写入全部随容器销毁。</p>
     *
     * @param request 由可信 Run 和固定快照构造的脚本资源定位
     * @param stdin 有界的脚本标准输入，不作为命令解释
     * @return 后置授权与严格审计成功后的脱敏输出
     */
    @Override
    public String execute(SkillReadRequest request, String stdin) {
        try {
            if (!executionEnabled()) throw failure(ApiErrorCode.SKILL_SANDBOX_DISABLED, "技能沙箱未启用", request, true);
            if (!DockerSkillSandbox.safePath(request.path()) || !request.path().endsWith(".py")
                    || stdin == null || stdin.getBytes(StandardCharsets.UTF_8).length > properties.getSandbox().getMaxInputBytes()) {
                throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "只允许执行包内 Python 脚本和有界输入", request, true);
            }
            java.util.Map<String, String> files = workUnit.execute(() -> prepareExecution(request));
            // 审计不可用时禁止创建容器，不能将严格失败降级成普通工具错误。
            executionAudit(request, "STARTED", "技能沙箱开始执行");
            try (var collection=artifacts==null?null:artifacts.begin(request);
                 var execution = sandbox.open(request, java.util.Map.of())) {
                String output = collection==null?execution.execute(files, stdin):execution.execute(files,stdin,collection);
                // 连接和认证材料清理成功后才能写成功审计；句柄须幂等关闭，交付前仍复核撤销。
                execution.close();
                // 端点策略复核可能包含DNS解析，放在事务外，避免出站等待持有Run/快照锁。
                execution.verifyAccess();
                workUnit.execute(() -> {
                    checkExecutionState(request);
                    ResolvedResource current = resolve(request, snapshots.lock(request.principal().tenantId(), request.runId()));
                    if (current.failure() != null) throw current.failure();
                    executionAudit(request, "SUCCEEDED", "技能沙箱执行成功");
                    return null;
                });
                if(collection!=null)collection.complete();
                return output;
            }
        } catch (SkillAccessException failure) {
            try {
                executionAudit(request, "FAILED", failure.code().name() + " errorId=" + failure.errorId());
            } catch (RuntimeException auditFailure) {
                // 失败审计本身不可用时，仍保留同一关联编号，以严格基础设施失败收口而非伪造审计成功。
                SkillAccessException controlled = infrastructureFailure(request, auditFailure);
                logExecutionFailure(request, controlled);
                throw controlled;
            }
            logExecutionFailure(request, failure);
            throw failure;
        } catch (RuntimeException failure) {
            SkillAccessException controlled = infrastructureFailure(request, failure);
            logExecutionFailure(request, controlled);
            throw controlled;
        }
    }

    private java.util.Map<String, String> prepareExecution(SkillReadRequest request) {
        checkExecutionState(request);
        snapshots.lock(request.principal().tenantId(), request.runId());
        if (records.findByCall(request.principal().tenantId(), request.runId(), request.modelCallId()).isPresent()) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_DUPLICATE, "本次脚本调用已经准备过，请勿重复执行", request, true);
        }
        ResolvedResource script = resolve(request, snapshots.lock(request.principal().tenantId(), request.runId()));
        if (script.failure() != null) throw script.failure();
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        resources.list(request.principal().tenantId(), request.skillId(), request.versionId())
                .forEach(resource -> files.put(resource.path(), resource.content()));
        versions.find(request.principal().tenantId(), request.skillId(), request.versionId())
                .ifPresent(version -> files.put("SKILL.md", version.content()));
        // 每个同版本资源都计入现有累计读取预算，不能通过一次脚本调用绕过资源字节限额。
        int bytes = files.values().stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
        List<SkillLoadRecord> budget = records.listForBudget(request.principal().tenantId(), request.runId());
        int delivered = usedBytes(budget, request);
        if (budget.size() >= properties.getMaxLoadAttempts() || bytes > properties.getMaxLoadedBytes() - delivered) {
            throw failure(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED, "本轮技能读取已达上限", request, true);
        }
        records.insert(new SkillLoadRecord(UUID.randomUUID(), request.principal().tenantId(), request.runId(),
                request.modelCallId(), budget.size() + 1, request.skillId(), request.versionId(), request.path(),
                SkillLoadStatus.SANDBOX_PREPARED, 0, 0, null, null, clock.instant()));
        executionAudit(request, "PREPARED", "固定技能资源已预留沙箱预算，尚未执行");
        return java.util.Map.copyOf(files);
    }

    private void checkExecutionState(SkillReadRequest request) {
        if (!executionEnabled()) throw failure(ApiErrorCode.SKILL_SANDBOX_DISABLED, "技能沙箱已关闭", request, true);
        // tenant 和 principal 均取自认证后的 Run 请求，模型输入不能覆盖；终态和审批等待均禁止执行。
        boolean active = runs.findByTenantAndAgentAndId(request.principal().tenantId(), request.agentId(), request.runId())
                .filter(run -> run.principalId().equals(request.principal().principalId())
                        && run.status() == com.cmagent.core.domain.RunStatus.RUNNING).isPresent();
        if (!active) throw failure(ApiErrorCode.SKILL_ACCESS_REVOKED, "本轮运行已结束或无权执行技能", request, true);
    }

    private void executionAudit(SkillReadRequest request, String status, String message) {
        audit.append(request.principal().tenantId(), request.principal().principalId(), "SKILL_EXECUTE", "RUN",
                request.runId().toString(), status, "skillId=" + request.skillId() + " versionId=" + request.versionId()
                        + " toolCallId=" + request.modelCallId() + " " + message);
    }

    private static void logExecutionFailure(SkillReadRequest request, SkillAccessException failure) {
        // 文件存储/下载服务已记录同一异常时只补严格失败审计，不重复技术诊断。
        if(!failure.claimDiagnostic())return;
        String template = "技能沙箱失败。errorId={}, operation=SKILL_EXECUTE, errorCode={}, tenantId={}, principalId={}, resourceType=SKILL, resourceId={}, versionId={}, runId={}, toolCallId={}";
        Object[] context = {failure.errorId(), failure.code(), request.principal().tenantId(),
                request.principal().principalId(), request.skillId(), request.versionId(), request.runId(), request.modelCallId()};
        if (failure.code() == ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE
                || failure.code() == ApiErrorCode.AUDIT_UNAVAILABLE || failure.code() == ApiErrorCode.PERSISTENCE_UNAVAILABLE) {
            RuntimeException safe = new RuntimeException(failure.safeMessage());
            safe.setStackTrace(failure.getCause() == null ? failure.getStackTrace() : failure.getCause().getStackTrace());
            Object[] diagnostic = java.util.Arrays.copyOf(context, context.length + 1);
            diagnostic[context.length] = safe;
            log.error(template, diagnostic);
        } else if (failure.code() == ApiErrorCode.SKILL_SANDBOX_TIMEOUT || failure.code() == ApiErrorCode.SKILL_SANDBOX_FAILED) {
            log.error(template, context);
        } else {
            log.warn(template, context);
        }
    }

    private static SkillAccessException infrastructureFailure(SkillReadRequest request, RuntimeException original) {
        ApiErrorCode code = original instanceof com.cmagent.server.audit.AuditPersistenceException
                ? ApiErrorCode.AUDIT_UNAVAILABLE
                : original instanceof org.springframework.dao.DataAccessException
                ? ApiErrorCode.PERSISTENCE_UNAVAILABLE : ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE;
        String message = switch (code) {
            case AUDIT_UNAVAILABLE -> "技能沙箱审计服务不可用，请联系管理员";
            case PERSISTENCE_UNAVAILABLE -> "技能沙箱数据服务不可用，请联系管理员";
            default -> "技能沙箱基础服务不可用，请联系管理员";
        };
        SkillAccessException controlled = failure(code, message, request, true);
        // 只保留原始异常的堆栈位置，不保留可能含凭据、SQL 或内部地址的异常消息。
        controlled.setStackTrace(original.getStackTrace());
        return controlled;
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
        int delivered = usedBytes(budget, request);
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
        if (existing.status() == SkillLoadStatus.SANDBOX_PREPARED) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_DUPLICATE,
                    "本次调用标识已用于沙箱资源准备", request, true);
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
        boolean allowed = reference.origin() == SkillSnapshotOrigin.BINDING
                ? bindingAllowed(request, reference, definition)
                : trialAllowed(request, reference, definition);
        if (!allowed) {
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

    private boolean bindingAllowed(SkillReadRequest request, SkillSnapshotRef reference, SkillDefinition definition) {
        AgentSkillBinding binding = bindings.find(
                request.principal().tenantId(), request.agentId(), request.skillId()).orElse(null);
        return definition.enabled() && definition.accessEpoch() == reference.accessEpoch()
                && binding != null && binding.id().equals(reference.bindingId());
    }

    private boolean trialAllowed(SkillReadRequest request, SkillSnapshotRef reference, SkillDefinition definition) {
        SkillTrial trial = trials.find(request.principal().tenantId(), request.runId()).orElse(null);
        // 显式试运行允许尚未启用的候选版本；访问纪元改变仍会立即撤销其快照读取权。
        return trial != null && trial.runId().equals(reference.authorizationId())
                && trial.skillId().equals(reference.skillId()) && trial.versionId().equals(reference.versionId())
                && trial.agentId().equals(request.agentId()) && trial.createdBy().equals(request.principal().principalId())
                && definition.accessEpoch() == reference.accessEpoch();
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

    /**
     * 沙箱准备不计作模型交付，但按其不可变固定版本资源大小预留相同预算。
     * 该记录持久化且受快照锁串行保护，恢复或跨实例请求不能通过零交付字节绕过限额。
     */
    private int usedBytes(List<SkillLoadRecord> budget, SkillReadRequest request) {
        int total = 0;
        for (SkillLoadRecord record : budget) {
            if (record.status() == SkillLoadStatus.SANDBOX_PREPARED) {
                SkillVersion version = versions.find(record.tenantId(), record.skillId(), record.versionId())
                        .orElseThrow(() -> failure(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                                "沙箱预算固定版本不可用", request, true));
                total += version.content().getBytes(StandardCharsets.UTF_8).length;
                total += resources.list(record.tenantId(), record.skillId(), record.versionId()).stream()
                        .mapToInt(SkillResource::byteLength).sum();
            } else {
                total += record.deliveredBytes();
            }
        }
        return total;
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
