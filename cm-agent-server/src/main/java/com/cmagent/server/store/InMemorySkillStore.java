package com.cmagent.server.store;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillBindingMode;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillPreflightRepository;
import com.cmagent.core.repository.SkillReleaseRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillTrialRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.server.service.SkillUnitOfWork;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 仅用于本地和测试 profile 的技能内存仓储与原子工作单元。
 *
 * <p>所有写操作只修改当前线程的暂存副本，最外层工作单元正常返回时才一次性发布。
 * 领域值均不可变，因此状态复制只复制 Map 容器。该实现不提供跨进程一致性，也不能用于生产。</p>
 */
public final class InMemorySkillStore implements SkillUnitOfWork {

    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadLocal<State> transactionState = new ThreadLocal<>();
    private volatile State currentState = new State();

    private final SkillDefinitionRepository definitions = new DefinitionView();
    private final SkillVersionRepository versions = new VersionView();
    private final SkillResourceRepository resources = new ResourceView();
    private final AgentSkillBindingRepository bindings = new BindingView();
    private final RunSkillSnapshotRepository snapshots = new SnapshotView();
    private final SkillLoadRecordRepository loads = new LoadView();
    private final SkillDependencyRepository dependencies = new DependencyView();
    private final SkillDependencyMappingRepository mappings = new MappingView();
    private final SkillPreflightRepository preflights = new PreflightView();
    private final SkillReleaseRepository releases = new ReleaseView();
    private final SkillTrialRepository trials = new TrialView();

    /** @return 技能定义仓储视图 */
    public SkillDefinitionRepository definitions() { return definitions; }
    /** @return 技能版本仓储视图 */
    public SkillVersionRepository versions() { return versions; }
    /** @return 技能资源仓储视图 */
    public SkillResourceRepository resources() { return resources; }
    /** @return Agent 技能绑定仓储视图 */
    public AgentSkillBindingRepository bindings() { return bindings; }
    /** @return Run 技能快照仓储视图 */
    public RunSkillSnapshotRepository snapshots() { return snapshots; }
    /** @return 技能读取记录仓储视图 */
    public SkillLoadRecordRepository loads() { return loads; }
    /** @return 技能版本依赖声明仓储视图 */
    public SkillDependencyRepository dependencies() { return dependencies; }
    /** @return 技能依赖映射仓储视图 */
    public SkillDependencyMappingRepository mappings() { return mappings; }
    /** @return 技能依赖预检仓储视图 */
    public SkillPreflightRepository preflights() { return preflights; }
    /** @return 技能发布事实仓储视图 */
    public SkillReleaseRepository releases() { return releases; }
    /** @return 技能试运行仓储视图 */
    public SkillTrialRepository trials() { return trials; }

    /**
     * 以可重入锁保护暂存副本，并仅在最外层成功返回时发布。
     *
     * <p>嵌套调用复用已有副本，防止内层提交覆盖外层尚未完成的审计或其他写入。</p>
     */
    @Override
    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation 不能为空");
        if (transactionState.get() != null) {
            return operation.get();
        }
        lock.lock();
        try {
            State staged = currentState.copy();
            transactionState.set(staged);
            T value = operation.get();
            currentState = staged;
            return value;
        } finally {
            transactionState.remove();
            lock.unlock();
        }
    }

    private <T> T read(Function<State, T> reader) {
        lock.lock();
        try {
            State staged = transactionState.get();
            return reader.apply(staged == null ? currentState : staged);
        } finally {
            lock.unlock();
        }
    }

    private State writable() {
        State staged = transactionState.get();
        if (staged == null) {
            throw new IllegalStateException("技能写入只能在工作单元内调用");
        }
        return staged;
    }

    private State lockable() {
        State staged = transactionState.get();
        if (staged == null) {
            throw new IllegalStateException("锁方法只能在技能工作单元内调用");
        }
        return staged;
    }

    private final class DefinitionView implements SkillDefinitionRepository {
        @Override
        public Optional<SkillDefinition> find(UUID tenantId, UUID skillId) {
            return read(state -> Optional.ofNullable(state.definitions.get(new DefinitionKey(tenantId, skillId))));
        }

        @Override
        public Optional<SkillDefinition> findByName(UUID tenantId, String name) {
            Objects.requireNonNull(name, "name 不能为空");
            return read(state -> state.definitions.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && name.equals(value.name()))
                    .findFirst());
        }

        @Override
        public ApiPageResponse<SkillDefinition> list(
                UUID tenantId, String query, Boolean enabled, ApiPageRequest page) {
            Objects.requireNonNull(tenantId, "tenantId 不能为空");
            Objects.requireNonNull(page, "page 不能为空");
            String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            return read(state -> page(state.definitions.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()))
                    .filter(value -> normalized.isEmpty()
                            || value.name().toLowerCase(Locale.ROOT).contains(normalized))
                    .filter(value -> enabled == null || value.enabled() == enabled)
                    .sorted(Comparator.comparing(SkillDefinition::updatedAt).reversed()
                            .thenComparing(SkillDefinition::id, InMemorySkillStore::compareUuidDescending))
                    .toList(), page));
        }

        @Override
        public SkillDefinition insert(SkillDefinition definition) {
            Objects.requireNonNull(definition, "definition 不能为空");
            State state = writable();
            DefinitionKey key = new DefinitionKey(definition.tenantId(), definition.id());
            if (state.definitions.containsKey(key)) {
                throw new IllegalStateException("技能已存在");
            }
            boolean duplicateName = state.definitions.values().stream().anyMatch(existing ->
                    existing.tenantId().equals(definition.tenantId())
                            && existing.name().equals(definition.name()));
            if (duplicateName) {
                throw new IllegalStateException("技能名称已存在");
            }
            state.definitions.put(key, definition);
            return definition;
        }

        @Override
        public SkillDefinition lock(UUID tenantId, UUID skillId) {
            State state = lockable();
            SkillDefinition value = state.definitions.get(new DefinitionKey(tenantId, skillId));
            if (value == null) {
                throw new NoSuchElementException("技能不存在");
            }
            return value;
        }

        @Override
        public boolean updateCurrent(SkillDefinition next, UUID expectedVersionId) {
            Objects.requireNonNull(next, "next 不能为空");
            Objects.requireNonNull(expectedVersionId, "expectedVersionId 不能为空");
            State state = writable();
            DefinitionKey key = new DefinitionKey(next.tenantId(), next.id());
            SkillDefinition current = state.definitions.get(key);
            if (current == null) {
                throw new NoSuchElementException("技能不存在");
            }
            if (!current.currentVersionId().equals(expectedVersionId)) {
                return false;
            }
            requireSameStableDefinition(current, next);
            state.definitions.put(key, next);
            return true;
        }

        @Override
        public void updateEnabled(SkillDefinition next) {
            Objects.requireNonNull(next, "next 不能为空");
            State state = writable();
            DefinitionKey key = new DefinitionKey(next.tenantId(), next.id());
            SkillDefinition current = state.definitions.get(key);
            if (current == null) {
                throw new NoSuchElementException("技能不存在");
            }
            requireSameStableDefinition(current, next);
            if (!current.currentVersionId().equals(next.currentVersionId())) {
                throw new IllegalArgumentException("启停更新不能修改当前版本");
            }
            state.definitions.put(key, next);
        }

        @Override
        public boolean updatePointers(SkillDefinition next, UUID expectedCandidateId, UUID expectedPublishedId) {
            Objects.requireNonNull(next, "next 不能为空");
            State state = writable();
            DefinitionKey key = new DefinitionKey(next.tenantId(), next.id());
            SkillDefinition current = state.definitions.get(key);
            if (current == null) {
                throw new NoSuchElementException("技能不存在");
            }
            if (!Objects.equals(current.candidateVersionId(), expectedCandidateId)
                    || !Objects.equals(current.publishedVersionId(), expectedPublishedId)) {
                return false;
            }
            requireSameStableDefinition(current, next);
            state.definitions.put(key, next);
            return true;
        }

        @Override
        public boolean updateDependencyMappingRevision(SkillDefinition next, long expectedRevision) {
            Objects.requireNonNull(next, "next 不能为空");
            State state = writable();
            DefinitionKey key = new DefinitionKey(next.tenantId(), next.id());
            SkillDefinition current = state.definitions.get(key);
            if (current == null) {
                throw new NoSuchElementException("技能不存在");
            }
            if (current.dependencyMappingRevision() != expectedRevision) {
                return false;
            }
            requireSameStableDefinition(current, next);
            state.definitions.put(key, next);
            return true;
        }
    }

    private final class VersionView implements SkillVersionRepository {
        @Override
        public void insert(SkillVersion version) {
            Objects.requireNonNull(version, "version 不能为空");
            State state = writable();
            VersionKey key = new VersionKey(version.tenantId(), version.skillId(), version.id());
            boolean duplicateNumber = state.versions.values().stream().anyMatch(existing ->
                    existing.tenantId().equals(version.tenantId())
                            && existing.skillId().equals(version.skillId())
                            && existing.versionNo() == version.versionNo());
            if (state.versions.containsKey(key) || duplicateNumber) {
                throw new IllegalStateException("技能版本已存在");
            }
            state.versions.put(key, version);
        }

        @Override
        public Optional<SkillVersion> find(UUID tenantId, UUID skillId, UUID versionId) {
            return read(state -> Optional.ofNullable(
                    state.versions.get(new VersionKey(tenantId, skillId, versionId))));
        }

        @Override
        public List<SkillVersion> list(UUID tenantId, UUID skillId) {
            return read(state -> state.versions.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && skillId.equals(value.skillId()))
                    .sorted(Comparator.comparingInt(SkillVersion::versionNo).reversed()
                            .thenComparing(SkillVersion::id, InMemorySkillStore::compareUuidDescending))
                    .toList());
        }
    }

    private final class ResourceView implements SkillResourceRepository {
        @Override
        public void insertAll(List<SkillResource> values) {
            Objects.requireNonNull(values, "resources 不能为空");
            State state = writable();
            Set<ResourceKey> batch = new HashSet<>();
            for (SkillResource value : values) {
                ResourceKey key = new ResourceKey(
                        value.tenantId(), value.skillId(), value.versionId(), value.path());
                if (!batch.add(key) || state.resources.containsKey(key)) {
                    throw new IllegalStateException("技能资源已存在");
                }
            }
            values.forEach(value -> state.resources.put(new ResourceKey(
                    value.tenantId(), value.skillId(), value.versionId(), value.path()), value));
        }

        @Override
        public List<SkillResource> list(UUID tenantId, UUID skillId, UUID versionId) {
            return read(state -> state.resources.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId())
                            && skillId.equals(value.skillId()) && versionId.equals(value.versionId()))
                    .sorted(Comparator.comparing(SkillResource::path))
                    .toList());
        }
    }

    private final class BindingView implements AgentSkillBindingRepository {
        @Override
        public List<AgentSkillBinding> list(UUID tenantId, UUID agentId) {
            return read(state -> state.bindings.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && agentId.equals(value.agentId()))
                    .sorted(Comparator.comparing(AgentSkillBinding::createdAt)
                            .thenComparing(AgentSkillBinding::id, InMemorySkillStore::compareUuidAscending))
                    .toList());
        }

        @Override
        public Optional<AgentSkillBinding> find(UUID tenantId, UUID agentId, UUID skillId) {
            return read(state -> Optional.ofNullable(
                    state.bindings.get(new BindingKey(tenantId, agentId, skillId))));
        }

        @Override
        public void insert(AgentSkillBinding binding) {
            Objects.requireNonNull(binding, "binding 不能为空");
            State state = writable();
            BindingKey key = new BindingKey(binding.tenantId(), binding.agentId(), binding.skillId());
            if (state.bindings.putIfAbsent(key, binding) != null) {
                throw new IllegalStateException("Agent 已绑定该技能");
            }
        }

        @Override
        public boolean delete(UUID tenantId, UUID agentId, UUID skillId) {
            return writable().bindings.remove(new BindingKey(tenantId, agentId, skillId)) != null;
        }

        @Override
        public long countBySkill(UUID tenantId, UUID skillId) {
            return read(state -> state.bindings.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && skillId.equals(value.skillId()))
                    .count());
        }

        @Override
        public List<AgentSkillBinding> listBySkill(UUID tenantId, UUID skillId) {
            return read(state -> state.bindings.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && skillId.equals(value.skillId()))
                    .sorted(Comparator.comparing(AgentSkillBinding::createdAt)
                            .thenComparing(AgentSkillBinding::id, InMemorySkillStore::compareUuidAscending))
                    .toList());
        }

        @Override
        public List<UUID> listFollowerAgentIds(UUID tenantId, UUID skillId) {
            return listBySkill(tenantId, skillId).stream()
                    .filter(value -> value.mode() == SkillBindingMode.FOLLOW_PUBLISHED)
                    .map(AgentSkillBinding::agentId).toList();
        }

        @Override
        public void lockAgent(UUID tenantId, UUID agentId) {
            Objects.requireNonNull(tenantId, "tenantId 不能为空");
            Objects.requireNonNull(agentId, "agentId 不能为空");
            lockable();
            // 外层可重入锁已经把同一 Agent 的计数、删除和插入串行化，JDBC 实现会改用行锁。
        }

        @Override
        public boolean updateStrategy(AgentSkillBinding next, long expectedRevision) {
            Objects.requireNonNull(next, "next 不能为空");
            State state = writable();
            BindingKey key = new BindingKey(next.tenantId(), next.agentId(), next.skillId());
            AgentSkillBinding current = state.bindings.get(key);
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            if (!current.id().equals(next.id()) || !current.boundBy().equals(next.boundBy())
                    || !current.createdAt().equals(next.createdAt())) {
                throw new IllegalArgumentException("绑定策略更新不能改变稳定身份");
            }
            state.bindings.put(key, next);
            return true;
        }
    }

    private final class SnapshotView implements RunSkillSnapshotRepository {
        @Override
        public void insert(RunSkillSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot 不能为空");
            State state = writable();
            SnapshotKey key = new SnapshotKey(snapshot.tenantId(), snapshot.runId());
            if (state.snapshots.putIfAbsent(key, snapshot) != null) {
                throw new IllegalStateException("运行技能快照已存在");
            }
        }

        @Override
        public Optional<RunSkillSnapshot> find(UUID tenantId, UUID runId) {
            return read(state -> Optional.ofNullable(state.snapshots.get(new SnapshotKey(tenantId, runId))));
        }

        @Override
        public RunSkillSnapshot lock(UUID tenantId, UUID runId) {
            RunSkillSnapshot snapshot = lockable().snapshots.get(new SnapshotKey(tenantId, runId));
            if (snapshot == null) {
                throw new NoSuchElementException("运行技能快照不存在");
            }
            return snapshot;
        }
    }

    private final class LoadView implements SkillLoadRecordRepository {
        @Override
        public void insert(SkillLoadRecord record) {
            Objects.requireNonNull(record, "record 不能为空");
            State state = writable();
            LoadKey key = new LoadKey(record.tenantId(), record.runId(), record.id());
            boolean duplicateCall = state.loads.values().stream().anyMatch(existing ->
                    existing.tenantId().equals(record.tenantId())
                            && existing.runId().equals(record.runId())
                            && existing.modelCallId().equals(record.modelCallId()));
            if (state.loads.containsKey(key) || duplicateCall) {
                throw new IllegalStateException("技能读取记录已存在");
            }
            state.loads.put(key, record);
        }

        @Override
        public Optional<SkillLoadRecord> findByCall(UUID tenantId, UUID runId, String modelCallId) {
            return read(state -> state.loads.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && runId.equals(value.runId())
                            && modelCallId.equals(value.modelCallId()))
                    .findFirst());
        }

        @Override
        public List<SkillLoadRecord> listForBudget(UUID tenantId, UUID runId) {
            return read(state -> state.loads.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && runId.equals(value.runId()))
                    .sorted(Comparator.comparingInt(SkillLoadRecord::attemptNo)
                            .thenComparing(SkillLoadRecord::id, InMemorySkillStore::compareUuidAscending))
                    .toList());
        }

        @Override
        public ApiPageResponse<SkillLoadRecord> list(UUID tenantId, UUID runId, ApiPageRequest page) {
            return read(state -> page(state.loads.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && runId.equals(value.runId()))
                    .sorted(Comparator.comparing(SkillLoadRecord::createdAt).reversed()
                            .thenComparing(SkillLoadRecord::id, InMemorySkillStore::compareUuidDescending))
                    .toList(), page));
        }
    }

    private final class DependencyView implements SkillDependencyRepository {
        @Override
        public void insertAll(List<SkillDependency> dependencies) {
            Objects.requireNonNull(dependencies, "dependencies 不能为空");
            State state = writable();
            Set<DependencyKey> batch = new HashSet<>();
            for (SkillDependency value : dependencies) {
                DependencyKey key = new DependencyKey(
                        value.tenantId(), value.skillId(), value.versionId(), value.logicalKey());
                if (!batch.add(key) || state.dependencies.containsKey(key)) {
                    throw new IllegalStateException("技能依赖已存在");
                }
            }
            dependencies.forEach(value -> state.dependencies.put(new DependencyKey(
                    value.tenantId(), value.skillId(), value.versionId(), value.logicalKey()), value));
        }

        @Override
        public List<SkillDependency> list(UUID tenantId, UUID skillId, UUID versionId) {
            return read(state -> state.dependencies.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId())
                            && skillId.equals(value.skillId()) && versionId.equals(value.versionId()))
                    .sorted(Comparator.comparingInt(SkillDependency::position)
                            .thenComparing(SkillDependency::logicalKey))
                    .toList());
        }
    }

    private final class MappingView implements SkillDependencyMappingRepository {
        @Override
        public Optional<SkillDependencyMapping> find(UUID tenantId, UUID skillId, String logicalKey) {
            return read(state -> Optional.ofNullable(
                    state.mappings.get(new MappingKey(tenantId, skillId, logicalKey))));
        }

        @Override
        public List<SkillDependencyMapping> list(UUID tenantId, UUID skillId) {
            return read(state -> state.mappings.values().stream()
                    .filter(value -> tenantId.equals(value.tenantId()) && skillId.equals(value.skillId()))
                    .sorted(Comparator.comparing(SkillDependencyMapping::logicalKey))
                    .toList());
        }

        @Override
        public SkillDependencyMapping save(SkillDependencyMapping mapping) {
            Objects.requireNonNull(mapping, "mapping 不能为空");
            writable().mappings.put(new MappingKey(
                    mapping.tenantId(), mapping.skillId(), mapping.logicalKey()), mapping);
            return mapping;
        }

        @Override
        public boolean delete(UUID tenantId, UUID skillId, String logicalKey) {
            return writable().mappings.remove(new MappingKey(tenantId, skillId, logicalKey)) != null;
        }

        @Override
        public void lockSkill(UUID tenantId, UUID skillId) {
            Objects.requireNonNull(tenantId, "tenantId 不能为空");
            Objects.requireNonNull(skillId, "skillId 不能为空");
            lockable();
        }
    }

    private final class PreflightView implements SkillPreflightRepository {
        @Override
        public SkillPreflightCheck save(SkillPreflightCheck check, List<SkillPreflightItem> items) {
            Objects.requireNonNull(check, "check 不能为空");
            Objects.requireNonNull(items, "items 不能为空");
            State state = writable();
            PreflightKey key = new PreflightKey(check.tenantId(), check.id());
            if (state.preflights.containsKey(key)) {
                throw new IllegalStateException("技能预检已存在");
            }
            Set<String> itemKeys = new HashSet<>();
            for (SkillPreflightItem item : items) {
                if (!check.id().equals(item.checkId())) {
                    throw new IllegalArgumentException("预检明细与汇总归属不一致");
                }
                String itemKey = item.agentId() == null ? item.logicalKey()
                        : item.agentId() + "|" + item.logicalKey();
                if (!itemKeys.add(itemKey)) {
                    throw new IllegalArgumentException("预检明细重复");
                }
            }
            state.preflights.put(key, check);
            items.forEach(item -> state.preflightItems.put(new PreflightItemKey(
                    item.checkId(), item.agentId(), item.logicalKey()), item));
            return check;
        }

        @Override
        public Optional<SkillPreflightCheck> find(UUID tenantId, UUID checkId) {
            return read(state -> Optional.ofNullable(state.preflights.get(new PreflightKey(tenantId, checkId))));
        }

        @Override
        public List<SkillPreflightItem> listItems(UUID tenantId, UUID checkId) {
            return read(state -> state.preflightItems.values().stream()
                    .filter(item -> checkId.equals(item.checkId())
                            && state.preflights.containsKey(new PreflightKey(tenantId, checkId)))
                    .sorted(Comparator.comparing(SkillPreflightItem::agentId,
                                    Comparator.nullsFirst(InMemorySkillStore::compareUuidAscending))
                            .thenComparing(SkillPreflightItem::logicalKey))
                    .toList());
        }

        @Override
        public List<SkillPreflightCheck> list(UUID tenantId, UUID skillId, UUID versionId) {
            return read(state -> state.preflights.values().stream()
                    .filter(check -> tenantId.equals(check.tenantId())
                            && skillId.equals(check.skillId()) && versionId.equals(check.versionId()))
                    .sorted(Comparator.comparing(SkillPreflightCheck::createdAt).reversed()
                            .thenComparing(SkillPreflightCheck::id, InMemorySkillStore::compareUuidDescending))
                    .toList());
        }
    }

    private final class ReleaseView implements SkillReleaseRepository {
        @Override
        public SkillRelease insert(SkillRelease release) {
            Objects.requireNonNull(release, "release 不能为空");
            State state = writable();
            ReleaseKey key = new ReleaseKey(release.tenantId(), release.skillId(), release.id());
            boolean duplicateNumber = state.releases.values().stream().anyMatch(existing ->
                    existing.tenantId().equals(release.tenantId())
                            && existing.skillId().equals(release.skillId())
                            && existing.releaseNo() == release.releaseNo());
            if (state.releases.containsKey(key) || duplicateNumber) {
                throw new IllegalStateException("技能发布记录已存在");
            }
            state.releases.put(key, release);
            return release;
        }

        @Override
        public Optional<SkillRelease> find(UUID tenantId, UUID releaseId) {
            return read(state -> state.releases.values().stream()
                    .filter(release -> tenantId.equals(release.tenantId()) && releaseId.equals(release.id()))
                    .findFirst());
        }

        @Override
        public List<SkillRelease> list(UUID tenantId, UUID skillId) {
            return read(state -> state.releases.values().stream()
                    .filter(release -> tenantId.equals(release.tenantId()) && skillId.equals(release.skillId()))
                    .sorted(Comparator.comparingLong(SkillRelease::releaseNo).reversed())
                    .toList());
        }

        @Override
        public Optional<SkillRelease> lockLatest(UUID tenantId, UUID skillId) {
            State state = lockable();
            return state.releases.values().stream()
                    .filter(release -> tenantId.equals(release.tenantId()) && skillId.equals(release.skillId()))
                    .max(Comparator.comparingLong(SkillRelease::releaseNo));
        }
    }

    private final class TrialView implements SkillTrialRepository {
        @Override
        public SkillTrial insert(SkillTrial trial) {
            Objects.requireNonNull(trial, "trial 不能为空");
            State state = writable();
            TrialKey key = new TrialKey(trial.tenantId(), trial.runId());
            if (state.trials.putIfAbsent(key, trial) != null) {
                throw new IllegalStateException("技能试运行已存在");
            }
            return trial;
        }

        @Override
        public Optional<SkillTrial> find(UUID tenantId, UUID runId) {
            return read(state -> Optional.ofNullable(state.trials.get(new TrialKey(tenantId, runId))));
        }

        @Override
        public List<SkillTrial> list(UUID tenantId, UUID skillId, UUID versionId) {
            return read(state -> state.trials.values().stream()
                    .filter(trial -> tenantId.equals(trial.tenantId())
                            && skillId.equals(trial.skillId()) && versionId.equals(trial.versionId()))
                    .sorted(Comparator.comparing(SkillTrial::createdAt).reversed()
                            .thenComparing(SkillTrial::runId, InMemorySkillStore::compareUuidDescending))
                    .toList());
        }

        @Override
        public boolean update(SkillTrial next, SkillTrialStatus expectedStatus) {
            Objects.requireNonNull(next, "next 不能为空");
            Objects.requireNonNull(expectedStatus, "expectedStatus 不能为空");
            State state = writable();
            TrialKey key = new TrialKey(next.tenantId(), next.runId());
            SkillTrial current = state.trials.get(key);
            if (current == null || current.status() != expectedStatus) {
                return false;
            }
            if (!current.runId().equals(next.runId()) || !current.tenantId().equals(next.tenantId())
                    || !current.skillId().equals(next.skillId()) || !current.versionId().equals(next.versionId())
                    || !current.agentId().equals(next.agentId())
                    || current.mappingRevision() != next.mappingRevision()) {
                throw new IllegalArgumentException("试运行稳定身份不能修改");
            }
            state.trials.put(key, next);
            return true;
        }

        @Override
        public SkillTrial lock(UUID tenantId, UUID runId) {
            SkillTrial trial = lockable().trials.get(new TrialKey(tenantId, runId));
            if (trial == null) {
                throw new NoSuchElementException("技能试运行不存在");
            }
            return trial;
        }
    }

    private static void requireSameStableDefinition(SkillDefinition current, SkillDefinition next) {
        if (!current.tenantId().equals(next.tenantId()) || !current.id().equals(next.id())
                || !current.name().equals(next.name()) || !current.createdAt().equals(next.createdAt())
                || !current.createdBy().equals(next.createdBy())) {
            throw new IllegalArgumentException("技能稳定身份不能修改");
        }
    }

    private static <T> ApiPageResponse<T> page(List<T> values, ApiPageRequest page) {
        long startLong = (long) page.page() * page.size();
        if (startLong >= values.size()) {
            return new ApiPageResponse<>(List.of(), values.size(), page.page(), page.size());
        }
        int start = Math.toIntExact(startLong);
        int end = Math.min(values.size(), start + page.size());
        return new ApiPageResponse<>(values.subList(start, end), values.size(), page.page(), page.size());
    }

    private static int compareUuidAscending(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    private static int compareUuidDescending(UUID left, UUID right) {
        return right.toString().compareTo(left.toString());
    }

    private static final class State {
        private final Map<DefinitionKey, SkillDefinition> definitions;
        private final Map<VersionKey, SkillVersion> versions;
        private final Map<ResourceKey, SkillResource> resources;
        private final Map<BindingKey, AgentSkillBinding> bindings;
        private final Map<SnapshotKey, RunSkillSnapshot> snapshots;
        private final Map<LoadKey, SkillLoadRecord> loads;
        private final Map<DependencyKey, SkillDependency> dependencies;
        private final Map<MappingKey, SkillDependencyMapping> mappings;
        private final Map<PreflightKey, SkillPreflightCheck> preflights;
        private final Map<PreflightItemKey, SkillPreflightItem> preflightItems;
        private final Map<ReleaseKey, SkillRelease> releases;
        private final Map<TrialKey, SkillTrial> trials;

        private State() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private State(
                Map<DefinitionKey, SkillDefinition> definitions,
                Map<VersionKey, SkillVersion> versions,
                Map<ResourceKey, SkillResource> resources,
                Map<BindingKey, AgentSkillBinding> bindings,
                Map<SnapshotKey, RunSkillSnapshot> snapshots,
                Map<LoadKey, SkillLoadRecord> loads,
                Map<DependencyKey, SkillDependency> dependencies,
                Map<MappingKey, SkillDependencyMapping> mappings,
                Map<PreflightKey, SkillPreflightCheck> preflights,
                Map<PreflightItemKey, SkillPreflightItem> preflightItems,
                Map<ReleaseKey, SkillRelease> releases,
                Map<TrialKey, SkillTrial> trials
        ) {
            this.definitions = definitions;
            this.versions = versions;
            this.resources = resources;
            this.bindings = bindings;
            this.snapshots = snapshots;
            this.loads = loads;
            this.dependencies = dependencies;
            this.mappings = mappings;
            this.preflights = preflights;
            this.preflightItems = preflightItems;
            this.releases = releases;
            this.trials = trials;
        }

        private State copy() {
            return new State(new HashMap<>(definitions), new HashMap<>(versions),
                    new HashMap<>(resources), new HashMap<>(bindings),
                    new HashMap<>(snapshots), new HashMap<>(loads),
                    new HashMap<>(dependencies), new HashMap<>(mappings),
                    new HashMap<>(preflights), new HashMap<>(preflightItems),
                    new HashMap<>(releases), new HashMap<>(trials));
        }
    }

    private record DefinitionKey(UUID tenantId, UUID skillId) { }
    private record VersionKey(UUID tenantId, UUID skillId, UUID versionId) { }
    private record ResourceKey(UUID tenantId, UUID skillId, UUID versionId, String path) { }
    private record BindingKey(UUID tenantId, UUID agentId, UUID skillId) { }
    private record SnapshotKey(UUID tenantId, UUID runId) { }
    private record LoadKey(UUID tenantId, UUID runId, UUID recordId) { }
    private record DependencyKey(UUID tenantId, UUID skillId, UUID versionId, String logicalKey) { }
    private record MappingKey(UUID tenantId, UUID skillId, String logicalKey) { }
    private record PreflightKey(UUID tenantId, UUID checkId) { }
    private record PreflightItemKey(UUID checkId, UUID agentId, String logicalKey) { }
    private record ReleaseKey(UUID tenantId, UUID skillId, UUID releaseId) { }
    private record TrialKey(UUID tenantId, UUID runId) { }
}
