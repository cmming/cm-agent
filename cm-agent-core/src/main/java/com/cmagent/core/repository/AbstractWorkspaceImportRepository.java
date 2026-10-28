package com.cmagent.core.repository;

import com.cmagent.core.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * memory/JDBC 共用的状态、配额和租约规则；子类只实现存储与同租户原子边界。
 * 令牌与 CAS 两者同时校验，防止已过期上传者提交迟到结果。
 */
public abstract class AbstractWorkspaceImportRepository implements WorkspaceImportRepository {
    /** 在同租户锁/短事务中执行，JDBC 必须锁真实 tenants 行且不得降级进程锁。 */
    protected abstract <T> T locked(UUID tenant, Supplier<T> action);
    /** @return 可信租户中的批次；调用方随后复核 owner/Agent */
    protected abstract Optional<WorkspaceImport> load(UUID tenant, UUID id);
    /** @return 包括已回收记录的请求关联，防止迟到重试重新占用容量或创建第二批次 */
    protected abstract Optional<WorkspaceImport> loadRequest(WorkspaceImportScope scope, UUID requestId);
    /** @return 未物理回收的批次，不能忽略已经逻辑撤销或到期的数据 */
    protected abstract List<WorkspaceImport> retained(UUID tenant);
    /** 新增时写不可变 manifest；更新只能改状态、确认集合、版本及期限。 */
    protected abstract void persist(WorkspaceImport value, boolean inserting);
    /** @return 批次租约；必须有明确数量上限 */
    protected abstract List<WorkspaceImportLease> leases(UUID tenant, UUID id);
    /** 在同租户锁内新增或更新一个租约。 */
    protected abstract void putLease(UUID tenant, UUID id, WorkspaceImportLease lease);
    /** 在同租户锁内只删除指定令牌。 */
    protected abstract void removeLease(UUID tenant, UUID id, UUID token);

    @Override public final boolean reserve(WorkspaceImport draft, WorkspaceImportLimits limits, Instant now) {
        if (draft.state() != WorkspaceImport.State.DRAFT || draft.reclaimed() || draft.version() != 1 || !draft.uploaded().isEmpty()
                || draft.createdAt().isAfter(now) || !draft.expiresAt().isAfter(now)
                || draft.expiresAt().isAfter(draft.createdAt().plus(limits.draftTtl()))
                || !withinLimits(draft, limits))
            throw new IllegalArgumentException("导入预留超过当前预算或期限");
        return locked(draft.scope().tenantId(), () -> {
            if (load(draft.scope().tenantId(), draft.id()).isPresent()) return false;
            if (draft.requestId() != null && loadRequest(draft.scope(), draft.requestId()).isPresent()) return false;
            var all = retained(draft.scope().tenantId());
            var own = all.stream().filter(v -> v.scope().principalId().equals(draft.scope().principalId())).toList();
            if (all.size() >= limits.tenantImports() || own.size() >= limits.ownerImports()
                    || all.stream().mapToLong(WorkspaceImport::totalBytes).sum() + draft.totalBytes() > limits.tenantBytes()
                    || own.stream().mapToLong(WorkspaceImport::totalBytes).sum() + draft.totalBytes() > limits.ownerBytes()) return false;
            persist(draft, true); return true;
        });
    }

    @Override public final Optional<WorkspaceImport> find(WorkspaceImportScope scope, UUID id) {
        return locked(scope.tenantId(), () -> visible(scope, id));
    }

    @Override public final Optional<WorkspaceImport> findByRequest(WorkspaceImportScope scope, UUID requestId) {
        Objects.requireNonNull(requestId);
        return locked(scope.tenantId(), () -> loadRequest(scope, requestId).filter(v -> v.scope().equals(scope)));
    }

    private Optional<WorkspaceImport> visible(WorkspaceImportScope scope, UUID id) {
        return load(scope.tenantId(), id).filter(v -> v.scope().equals(scope));
    }

    @Override public final Optional<WorkspaceImport> stored(WorkspaceImportScope scope, UUID id, UUID file, long version, UUID token, Instant now) {
        return locked(scope.tenantId(), () -> visible(scope, id).filter(v -> writable(v, version, token, now)
                && !v.uploaded().contains(file) && v.files().stream().anyMatch(f -> f.id().equals(file))).map(v -> {
                    var uploaded = new HashSet<>(v.uploaded()); uploaded.add(file);
                    var next = copy(v, v.state(), uploaded, v.expiresAt(), false); persist(next, false); return next;
                }));
    }

    @Override public final Optional<WorkspaceImport> complete(WorkspaceImportScope scope, UUID id, long version, UUID token,
            WorkspaceImportLimits limits, Instant now) {
        return locked(scope.tenantId(), () -> visible(scope, id).filter(v -> writable(v, version, token, now)
                && v.uploaded().size() == v.files().size() && withinLimits(v, limits)).map(v -> {
                    var next = copy(v, WorkspaceImport.State.READY, v.uploaded(), now.plus(limits.readyTtl()), false);
                    persist(next, false); return next;
                }));
    }

    @Override public final Optional<WorkspaceImport> revoke(WorkspaceImportScope scope, UUID id, long version, Instant now) {
        return locked(scope.tenantId(), () -> visible(scope, id).filter(v -> v.version() == version).map(v -> {
            if (v.state() == WorkspaceImport.State.REVOKED || v.state() == WorkspaceImport.State.EXPIRED) return v;
            var next = copy(v, v.expiresAt().isAfter(now) ? WorkspaceImport.State.REVOKED : WorkspaceImport.State.EXPIRED,
                    v.uploaded(), v.expiresAt(), false); persist(next, false); return next;
        }));
    }

    private boolean writable(WorkspaceImport value, long version, UUID token, Instant now) {
        return value.version() == version && value.state() == WorkspaceImport.State.DRAFT && value.expiresAt().isAfter(now)
                && active(value, now).stream().anyMatch(l -> l.token().equals(token) && l.kind() == WorkspaceImportLease.Kind.UPLOAD);
    }

    private List<WorkspaceImportLease> active(WorkspaceImport value, Instant now) {
        var found = leases(value.scope().tenantId(), value.id());
        for (var lease : found) if (!lease.until().isAfter(now)) removeLease(value.scope().tenantId(), value.id(), lease.token());
        return found.stream().filter(l -> l.until().isAfter(now)).toList();
    }

    @Override public final Optional<WorkspaceImportLease> acquire(WorkspaceImportScope scope, UUID id,
            WorkspaceImportLease.Kind kind, Duration duration, Instant now) {
        checkDuration(duration); Objects.requireNonNull(kind);
        return locked(scope.tenantId(), () -> visible(scope, id).flatMap(v -> {
            if (v.reclaimed()) return Optional.empty();
            var current = active(v, now);
            boolean dead = v.state() == WorkspaceImport.State.REVOKED || v.state() == WorkspaceImport.State.EXPIRED || !v.expiresAt().isAfter(now);
            boolean allowed = switch (kind) {
                case READ -> v.usable(now) && current.size() < 64 && current.stream().allMatch(l -> l.kind() == WorkspaceImportLease.Kind.READ);
                case UPLOAD -> v.state() == WorkspaceImport.State.DRAFT && !dead && current.isEmpty();
                case GC -> dead && current.isEmpty();
            };
            if (!allowed) return Optional.empty();
            if (kind == WorkspaceImportLease.Kind.GC && v.state() != WorkspaceImport.State.REVOKED && v.state() != WorkspaceImport.State.EXPIRED)
                persist(copy(v, WorkspaceImport.State.EXPIRED, v.uploaded(), v.expiresAt(), false), false);
            var lease = new WorkspaceImportLease(UUID.randomUUID(), kind, now.plus(duration)); putLease(scope.tenantId(), id, lease);
            return Optional.of(lease);
        }));
    }

    @Override public final boolean renew(WorkspaceImportScope scope, UUID id, UUID token, Duration duration, Instant now) {
        checkDuration(duration);
        return locked(scope.tenantId(), () -> visible(scope, id).map(v -> {
            var old = active(v, now).stream().filter(l -> l.token().equals(token)).findFirst().orElse(null);
            if (old == null || v.reclaimed() || old.kind() == WorkspaceImportLease.Kind.READ && !v.usable(now)
                    || old.kind() == WorkspaceImportLease.Kind.UPLOAD && (v.state() != WorkspaceImport.State.DRAFT || !v.expiresAt().isAfter(now))) return false;
            putLease(scope.tenantId(), id, new WorkspaceImportLease(token, old.kind(), now.plus(duration))); return true;
        }).orElse(false));
    }

    @Override public final void release(WorkspaceImportScope scope, UUID id, UUID token) {
        locked(scope.tenantId(), () -> { if (visible(scope, id).isPresent()) removeLease(scope.tenantId(), id, token); return true; });
    }

    @Override public final boolean reclaimed(WorkspaceImportScope scope, UUID id, UUID token, Instant now) {
        return locked(scope.tenantId(), () -> visible(scope, id).filter(v -> !v.reclaimed()
                && active(v, now).stream().anyMatch(l -> l.token().equals(token) && l.kind() == WorkspaceImportLease.Kind.GC)).map(v -> {
                    persist(copy(v, v.state(), v.uploaded(), v.expiresAt(), true), false);
                    removeLease(scope.tenantId(), id, token); return true;
                }).orElse(false));
    }

    private static void checkDuration(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("导入租约必须在一秒到五分钟之间");
    }

    private static boolean withinLimits(WorkspaceImport value, WorkspaceImportLimits limits) {
        // 部署者收紧预算后，旧 DRAFT 不能以创建时的宽预算发布新 READY。
        return value.files().size() <= limits.maxFiles() && value.totalBytes() <= limits.maxTotalBytes()
                && value.files().stream().allMatch(f -> f.sizeBytes() <= limits.maxFileBytes());
    }

    private static WorkspaceImport copy(WorkspaceImport v, WorkspaceImport.State state, Set<UUID> uploaded, Instant expires, boolean reclaimed) {
        return new WorkspaceImport(v.id(), v.scope(), v.displayName(), v.files(), v.manifestDigest(), state, uploaded,
                v.version() + 1, v.createdAt(), expires, reclaimed, v.requestId());
    }
}
