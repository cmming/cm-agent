package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillRuntimeBundle;
import com.cmagent.core.domain.SkillSnapshotRef;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SkillUnitOfWork;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 为 Run 创建不可变技能快照，并在恢复时复核绑定身份、访问纪元和历史版本。
 *
 * <p>快照缺失绝不回退到当前绑定，因为那会让旧 Run 在审批恢复时获得创建之后才出现的技能。
 * 停用后重新启用或解绑后重新绑定都会改变撤销凭据，旧快照因此永久失效。</p>
 */
@Service
public class SkillRuntimeService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final AgentSkillBindingRepository bindings;
    private final RunSkillSnapshotRepository snapshots;
    private final SkillUnitOfWork workUnit;
    private final SkillProperties properties;
    private final Clock clock;

    /**
     * 创建运行时技能服务。
     *
     * @param definitions 技能定义仓储
     * @param versions 不可变版本仓储
     * @param resources 版本资源仓储
     * @param bindings Agent 技能绑定仓储
     * @param snapshots Run 快照仓储
     * @param workUnit 技能原子工作单元
     * @param properties 技能功能和预算配置
     */
    @Autowired
    public SkillRuntimeService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                               SkillResourceRepository resources, AgentSkillBindingRepository bindings,
                               RunSkillSnapshotRepository snapshots, SkillUnitOfWork workUnit,
                               SkillProperties properties) {
        this(definitions, versions, resources, bindings, snapshots, workUnit,
                properties, Clock.systemUTC());
    }

    SkillRuntimeService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                        SkillResourceRepository resources, AgentSkillBindingRepository bindings,
                        RunSkillSnapshotRepository snapshots, SkillUnitOfWork workUnit,
                        SkillProperties properties, Clock clock) {
        this.definitions = Objects.requireNonNull(definitions, "definitions 不能为空");
        this.versions = Objects.requireNonNull(versions, "versions 不能为空");
        this.resources = Objects.requireNonNull(resources, "resources 不能为空");
        this.bindings = Objects.requireNonNull(bindings, "bindings 不能为空");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots 不能为空");
        this.workUnit = Objects.requireNonNull(workUnit, "workUnit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 在 Run 进入模型前固定当前已启用绑定；空集合也保存，重复调用复用原快照。
     *
     * @param principal 原 Run 的可信认证主体
     * @param run 已持久化的运行记录
     * @return 固定快照及其版本正文
     */
    public SkillRuntimeBundle prepare(PrincipalRef principal, RunRecord run) {
        requireRunOwner(principal, run);
        return workUnit.execute(() -> snapshots.find(principal.tenantId(), run.id())
                .map(snapshot -> restoreInside(principal, run, snapshot))
                .orElseGet(() -> createInside(principal, run)));
    }

    /**
     * 恢复原 Run 的技能集合；缺失、停用、解绑或纪元变化均拒绝继续。
     *
     * @param principal 必须与原 Run 主体一致的可信认证主体
     * @param run 待恢复的运行记录
     * @return 原快照固定的历史版本集合
     */
    public SkillRuntimeBundle restore(PrincipalRef principal, RunRecord run) {
        requireRunOwner(principal, run);
        return workUnit.execute(() -> restoreInside(
                principal, run, requireSnapshot(snapshots, principal.tenantId(), run.id())));
    }

    static RunSkillSnapshot requireSnapshot(
            RunSkillSnapshotRepository repository, UUID tenantId, UUID runId) {
        return repository.find(tenantId, runId).orElseThrow(() -> failure(
                ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE, "本轮技能快照不可用，请重新发起对话", true));
    }

    private SkillRuntimeBundle createInside(PrincipalRef principal, RunRecord run) {
        bindings.lockAgent(principal.tenantId(), run.agentId());
        List<SkillSnapshotRef> references = new ArrayList<>();
        List<SkillVersionView> views = new ArrayList<>();
        int totalBytes = 0;
        if (properties.isEnabled()) {
            for (AgentSkillBinding binding : bindings.list(principal.tenantId(), run.agentId())) {
                SkillDefinition definition = definitions.lock(principal.tenantId(), binding.skillId());
                if (!definition.enabled()) {
                    continue;
                }
                SkillVersionView view = view(definition, definition.currentVersionId());
                totalBytes = Math.addExact(totalBytes, bytes(view));
                if (totalBytes > properties.getMaxRunBytes()) {
                    throw failure(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED,
                            "本轮绑定技能内容超过准备上限", true);
                }
                references.add(new SkillSnapshotRef(definition.id(), view.version().id(),
                        binding.id(), definition.accessEpoch()));
                views.add(view);
            }
        }
        RunSkillSnapshot snapshot = new RunSkillSnapshot(principal.tenantId(), run.id(), run.agentId(),
                1, references, clock.instant());
        snapshots.insert(snapshot);
        return new SkillRuntimeBundle(snapshot, views);
    }

    private SkillRuntimeBundle restoreInside(
            PrincipalRef principal, RunRecord run, RunSkillSnapshot snapshot) {
        if (!snapshot.agentId().equals(run.agentId())) {
            throw failure(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE, "本轮技能快照归属不一致", true);
        }
        if (!properties.isEnabled() && !snapshot.skills().isEmpty()) {
            throw failure(ApiErrorCode.SKILL_FEATURE_DISABLED, "技能功能已关闭，请重新发起对话", true);
        }
        bindings.lockAgent(principal.tenantId(), run.agentId());
        List<SkillVersionView> views = new ArrayList<>();
        for (SkillSnapshotRef reference : snapshot.skills()) {
            SkillDefinition current = definitions.lock(principal.tenantId(), reference.skillId());
            AgentSkillBinding binding = bindings.find(principal.tenantId(), run.agentId(), reference.skillId())
                    .orElseThrow(() -> revoked());
            boolean allowed = current.enabled()
                    && current.accessEpoch() == reference.accessEpoch()
                    && binding.id().equals(reference.bindingId())
                    && binding.skillId().equals(reference.skillId())
                    && binding.agentId().equals(run.agentId());
            if (!allowed) {
                throw revoked();
            }
            views.add(view(current, reference.versionId()));
        }
        return new SkillRuntimeBundle(snapshot, views);
    }

    private SkillVersionView view(SkillDefinition definition, UUID versionId) {
        SkillVersion version = versions.find(definition.tenantId(), definition.id(), versionId)
                .orElseThrow(() -> failure(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                        "本轮固定的技能版本不可用", true));
        return new SkillVersionView(definition, version,
                resources.list(definition.tenantId(), definition.id(), versionId));
    }

    private static int bytes(SkillVersionView view) {
        int total = view.version().content().getBytes(StandardCharsets.UTF_8).length;
        for (var resource : view.resources()) {
            total = Math.addExact(total, resource.byteLength());
        }
        return total;
    }

    private static void requireRunOwner(PrincipalRef principal, RunRecord run) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(run, "run 不能为空");
        if (!principal.tenantId().equals(run.tenantId())
                || !principal.principalId().equals(run.principalId())) {
            throw failure(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE, "本轮技能快照不可用", true);
        }
    }

    private static SkillAccessException revoked() {
        return failure(ApiErrorCode.SKILL_ACCESS_REVOKED,
                "本轮使用的技能已停用或解绑，请重新发起对话", true);
    }

    private static SkillAccessException failure(ApiErrorCode code, String message, boolean fatal) {
        return new SkillAccessException(code, message, UUID.randomUUID().toString(), fatal);
    }
}
