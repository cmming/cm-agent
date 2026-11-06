package com.cmagent.server.store;

import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.core.repository.LocalProjectRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * local/test阶段的私有项目内存仓储；重启丢失，不能用于生产恢复。
 *
 * <p>当前未注册Bean，JDBC和启动门禁完成前不开放项目接口。每个项目的条件更新在
 * ConcurrentHashMap.computeIfPresent内原子完成；列表先做精确tenant/owner过滤，再做游标和上限。</p>
 */
public final class InMemoryLocalProjectRepository implements LocalProjectRepository {
    /** 身份以tenant+项目共同限定；不依赖UUID全局唯一性代替租户隔离。 */
    private final ConcurrentHashMap<Key, LocalProject> projects = new ConcurrentHashMap<>();
    /** 与未来SQL一致的updatedAt/id倒序，UUID使用字符串序避免Java有符号比较与数据库排序不同。 */
    private static final Comparator<LocalProject> ORDER = Comparator.comparing(LocalProject::updatedAt).reversed()
            .thenComparing(value -> value.id().toString(), Comparator.reverseOrder());

    @Override public Optional<LocalProject> find(UUID tenantId, String ownerId, UUID projectId) {
        return Optional.ofNullable(projects.get(new Key(tenantId, projectId))).filter(value -> value.ownerId().equals(ownerId));
    }

    @Override public List<LocalProject> list(UUID tenantId, String ownerId, LocalProjectPageRequest page, boolean includeArchived) {
        return projects.values().stream()
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId))
                .filter(value -> includeArchived || value.state() == LocalProject.State.ACTIVE)
                .filter(value -> page.beforeUpdatedAt() == null || value.updatedAt().isBefore(page.beforeUpdatedAt())
                        || value.updatedAt().equals(page.beforeUpdatedAt()) && value.id().toString().compareTo(page.beforeId().toString()) < 0)
                .sorted(ORDER).limit(page.limit()).toList();
    }

    @Override public boolean insert(LocalProject project) {
        if (project.state() != LocalProject.State.ACTIVE || project.version() != 1)
            throw new IllegalArgumentException("项目只能以初始活动版本新增");
        return projects.putIfAbsent(new Key(project.tenantId(), project.id()), project) == null;
    }

    @Override public boolean rename(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, String name, Instant now) {
        var changed = new AtomicBoolean();
        projects.computeIfPresent(new Key(tenantId, projectId), (key, value) -> {
            if (!matches(value, ownerId, expectedVersion)) return value;
            var next = value.rename(name, now);
            changed.set(true);
            return next;
        });
        return changed.get();
    }

    @Override public boolean archive(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, Instant now) {
        var changed = new AtomicBoolean();
        projects.computeIfPresent(new Key(tenantId, projectId), (key, value) -> {
            if (!matches(value, ownerId, expectedVersion)) return value;
            var next = value.archive(now);
            changed.set(true);
            return next;
        });
        return changed.get();
    }

    private static boolean matches(LocalProject value, String owner, long version) {
        return value.ownerId().equals(owner) && value.version() == version && value.state() == LocalProject.State.ACTIVE;
    }

    /**
     * @param tenantId 可信租户，不能用客户端覆盖
     * @param projectId 租户内项目标识
     */
    private record Key(UUID tenantId, UUID projectId) {}
}

