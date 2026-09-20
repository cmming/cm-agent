package com.cmagent.server.store;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillResourceRepository;
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
        public void lockAgent(UUID tenantId, UUID agentId) {
            Objects.requireNonNull(tenantId, "tenantId 不能为空");
            Objects.requireNonNull(agentId, "agentId 不能为空");
            lockable();
            // 外层可重入锁已经把同一 Agent 的计数、删除和插入串行化，JDBC 实现会改用行锁。
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

        private State() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private State(
                Map<DefinitionKey, SkillDefinition> definitions,
                Map<VersionKey, SkillVersion> versions,
                Map<ResourceKey, SkillResource> resources,
                Map<BindingKey, AgentSkillBinding> bindings,
                Map<SnapshotKey, RunSkillSnapshot> snapshots,
                Map<LoadKey, SkillLoadRecord> loads
        ) {
            this.definitions = definitions;
            this.versions = versions;
            this.resources = resources;
            this.bindings = bindings;
            this.snapshots = snapshots;
            this.loads = loads;
        }

        private State copy() {
            return new State(new HashMap<>(definitions), new HashMap<>(versions),
                    new HashMap<>(resources), new HashMap<>(bindings),
                    new HashMap<>(snapshots), new HashMap<>(loads));
        }
    }

    private record DefinitionKey(UUID tenantId, UUID skillId) { }
    private record VersionKey(UUID tenantId, UUID skillId, UUID versionId) { }
    private record ResourceKey(UUID tenantId, UUID skillId, UUID versionId, String path) { }
    private record BindingKey(UUID tenantId, UUID agentId, UUID skillId) { }
    private record SnapshotKey(UUID tenantId, UUID runId) { }
    private record LoadKey(UUID tenantId, UUID runId, UUID recordId) { }
}
