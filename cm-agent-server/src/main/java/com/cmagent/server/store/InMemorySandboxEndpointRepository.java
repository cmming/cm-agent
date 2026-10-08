package com.cmagent.server.store;

import com.cmagent.core.domain.SandboxEndpoint;
import com.cmagent.core.repository.SandboxEndpointRepository;
import java.util.*;
import java.util.function.Supplier;

/** 仅 local/test 的原子暂存实现；失败恢复状态，不作为生产存储。 */
public class InMemorySandboxEndpointRepository implements SandboxEndpointRepository {
    /** 同一监视器下发布端点与默认值，避免默认切换和删除竞争。 */
    private Map<UUID, SandboxEndpoint> endpoints = new HashMap<>();
    /** 默认选择以租户为键，禁止全局共享选择。 */
    private Map<UUID, UUID> defaults = new HashMap<>();
    @Override public synchronized <T> T atomic(UUID tenantId, Supplier<T> operation) {
        var oldEndpoints = new HashMap<>(endpoints); var oldDefaults = new HashMap<>(defaults);
        try { return operation.get(); }
        catch (RuntimeException | Error failure) { endpoints = oldEndpoints; defaults = oldDefaults; throw failure; }
    }
    @Override public synchronized List<SandboxEndpoint> list(UUID tenant) {
        return endpoints.values().stream().filter(e -> e.tenantId().equals(tenant) && !e.deleted())
                .sorted(Comparator.comparing(SandboxEndpoint::createdAt)).toList();
    }
    @Override public synchronized Optional<SandboxEndpoint> find(UUID tenant, UUID id) {
        return Optional.ofNullable(endpoints.get(id)).filter(e -> e.tenantId().equals(tenant) && !e.deleted());
    }
    @Override public synchronized void insert(SandboxEndpoint endpoint) {
        if (endpoints.putIfAbsent(endpoint.id(), endpoint) != null) throw new IllegalStateException("端点已存在");
    }
    @Override public synchronized boolean update(SandboxEndpoint endpoint, long expected) {
        var old = find(endpoint.tenantId(), endpoint.id()).orElse(null);
        if (old == null || old.revision() != expected) return false;
        endpoints.put(endpoint.id(), endpoint); return true;
    }
    @Override public synchronized Optional<UUID> defaultId(UUID tenant) { return Optional.ofNullable(defaults.get(tenant)); }
    @Override public synchronized void setDefault(UUID tenant, UUID id) {
        if (id == null) defaults.remove(tenant);
        else { if (find(tenant, id).isEmpty()) throw new IllegalArgumentException("端点不存在"); defaults.put(tenant, id); }
    }
}
