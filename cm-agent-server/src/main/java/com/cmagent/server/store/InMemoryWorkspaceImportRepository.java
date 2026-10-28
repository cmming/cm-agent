package com.cmagent.server.store;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.AbstractWorkspaceImportRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 仅 local/test 的原子元数据；单监视器使配额、状态与租约遵循同一临界区，不代替生产持久化。 */
public final class InMemoryWorkspaceImportRepository extends AbstractWorkspaceImportRepository {
    /** 生命周期属于本仓储实例，服务重启不保留。 */
    private final Map<String, WorkspaceImport> values = new HashMap<>();
    /** 租约以批次和不可预测令牌区分，允许多个在途读取。 */
    private final Map<String, Map<UUID, WorkspaceImportLease>> held = new HashMap<>();
    @Override protected synchronized <T> T locked(UUID tenant, Supplier<T> action) { return action.get(); }
    @Override protected Optional<WorkspaceImport> load(UUID tenant, UUID id) {
        return Optional.ofNullable(values.get(tenant + ":" + id));
    }
    @Override protected List<WorkspaceImport> retained(UUID tenant) {
        return values.values().stream().filter(v -> v.scope().tenantId().equals(tenant) && !v.reclaimed()).toList();
    }
    @Override protected Optional<WorkspaceImport> loadRequest(WorkspaceImportScope scope, UUID requestId) {
        return values.values().stream().filter(v -> v.scope().equals(scope) && requestId.equals(v.requestId())).findFirst();
    }
    @Override protected void persist(WorkspaceImport value, boolean inserting) {
        String key = value.scope().tenantId() + ":" + value.id();
        if (inserting && values.containsKey(key)) throw new IllegalStateException("导入标识已存在");
        values.put(key, value);
    }
    @Override protected List<WorkspaceImportLease> leases(UUID tenant, UUID id) {
        return List.copyOf(held.getOrDefault(tenant + ":" + id, Map.of()).values());
    }
    @Override protected void putLease(UUID tenant, UUID id, WorkspaceImportLease lease) {
        held.computeIfAbsent(tenant + ":" + id, unused -> new HashMap<>()).put(lease.token(), lease);
    }
    @Override protected void removeLease(UUID tenant, UUID id, UUID token) {
        String key = tenant + ":" + id;
        var found = held.get(key); if (found != null) { found.remove(token); if (found.isEmpty()) held.remove(key); }
    }
    @Override public synchronized List<WorkspaceImport> cleanupCandidates(Instant now) {
        return values.values().stream().filter(v -> !v.reclaimed() && (v.state() == WorkspaceImport.State.REVOKED
                || v.state() == WorkspaceImport.State.EXPIRED || !v.expiresAt().isAfter(now)))
                .sorted(Comparator.comparing(WorkspaceImport::expiresAt).thenComparing(WorkspaceImport::id)).limit(100).toList();
    }
}
