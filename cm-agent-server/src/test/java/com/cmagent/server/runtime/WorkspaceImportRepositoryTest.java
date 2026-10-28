package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.WorkspaceImportRepository;
import com.cmagent.server.store.InMemoryWorkspaceImportRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceImportRepositoryTest {
    @Test void 请求关联在上传完成撤销回收后仍稳定且不能创建第二批次() {
        UUID requestId=UUID.randomUUID();var base=draft();
        var value=new WorkspaceImport(base.id(),base.scope(),base.displayName(),base.files(),base.manifestDigest(),base.state(),base.uploaded(),base.version(),base.createdAt(),base.expiresAt(),false,requestId);
        assertThat(rows.reserve(value,limits,now)).isTrue();assertThat(rows.findByRequest(scope,requestId)).contains(value);
        var other=draft();var duplicate=new WorkspaceImport(other.id(),other.scope(),other.displayName(),other.files(),other.manifestDigest(),other.state(),other.uploaded(),other.version(),other.createdAt(),other.expiresAt(),false,requestId);
        assertThat(rows.reserve(duplicate,limits,now)).isFalse();
        rows.revoke(scope,value.id(),1,now);var gc=rows.acquire(scope,value.id(),WorkspaceImportLease.Kind.GC,Duration.ofMinutes(1),now).orElseThrow();
        rows.reclaimed(scope,value.id(),gc.token(),now);
        assertThat(rows.findByRequest(scope,requestId).orElseThrow().reclaimed()).isTrue();assertThat(rows.reserve(duplicate,limits,now)).isFalse();
        assertThat(rows.findByRequest(new WorkspaceImportScope(scope.tenantId(),"other",scope.agentId()),requestId)).isEmpty();
    }
    /** 合成固定时钟让租约边界无需真实等待。 */
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");
    /** 真实领域策略，不降低硬界。 */
    private final WorkspaceImportLimits limits = WorkspaceImportLimits.defaults();
    /** 本测试独享原子内存仓储，不依赖 Spring 或部署数据。 */
    private final WorkspaceImportRepository rows = new InMemoryWorkspaceImportRepository();
    /** 合成已认证归属。 */
    private final WorkspaceImportScope scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID());
    private WorkspaceImport draft() {
        return WorkspaceImport.draft(UUID.randomUUID(), scope, "合成目录", List.of(new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 1, "a".repeat(64))),
                now, now.plus(limits.draftTtl()));
    }
    @Test void 并发预留不能突破跨智能体主体配额() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> rows.reserve(draft(), limits, now)));
            int accepted = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(8);
        }
        var anotherAgent = new WorkspaceImportScope(scope.tenantId(), scope.principalId(), UUID.randomUUID());
        assertThat(rows.reserve(WorkspaceImport.draft(UUID.randomUUID(), anotherAgent, "另一个智能体", draft().files(), now, now.plusSeconds(60)), limits, now)).isFalse();
    }
    @Test void 未完成草稿不能发布且过期上传令牌拒绝() {
        var value = draft(); assertThat(rows.reserve(value, limits, now)).isTrue();
        var lease = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.UPLOAD, Duration.ofSeconds(1), now).orElseThrow();
        assertThat(rows.complete(scope, value.id(), 1, lease.token(), limits, now)).isEmpty();
        assertThat(rows.stored(scope, value.id(), value.files().getFirst().id(), 1, lease.token(), now.plusSeconds(1))).isEmpty();
        var next = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.UPLOAD, Duration.ofMinutes(1), now.plusSeconds(1)).orElseThrow();
        rows.release(scope, value.id(), lease.token());
        assertThat(rows.stored(scope, value.id(), value.files().getFirst().id(), 1, next.token(), now.plusSeconds(1))).isPresent();
        assertThat(rows.stored(scope, value.id(), value.files().getFirst().id(), 2, next.token(), now.plusSeconds(1))).isEmpty();
    }
    @Test void 所有权边界包含租户主体与智能体且不泄露状态() {
        var value = draft(); rows.reserve(value, limits, now);
        for (var other : List.of(new WorkspaceImportScope(UUID.randomUUID(), scope.principalId(), scope.agentId()),
                new WorkspaceImportScope(scope.tenantId(), "other", scope.agentId()), new WorkspaceImportScope(scope.tenantId(), scope.principalId(), UUID.randomUUID()))) {
            assertThat(rows.find(other, value.id())).isEmpty(); assertThat(rows.revoke(other, value.id(), 1, now)).isEmpty();
            assertThat(rows.acquire(other, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now)).isEmpty();
        }
    }
    @Test void 撤销立即禁用但共享读取引用归还前不能回收() {
        var value = ready();
        var first = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now).orElseThrow();
        var second = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now).orElseThrow();
        var revoked = rows.revoke(scope, value.id(), value.version(), now).orElseThrow();
        assertThat(revoked.usable(now)).isFalse();
        assertThat(rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now)).isEmpty();
        assertThat(rows.renew(scope, value.id(), second.token(), Duration.ofMinutes(1), now)).isFalse();
        rows.release(scope, value.id(), first.token());
        assertThat(rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now)).isEmpty();
        rows.release(scope, value.id(), second.token());
        var gc = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now).orElseThrow();
        assertThat(rows.reclaimed(scope, value.id(), UUID.randomUUID(), now)).isFalse();
        assertThat(rows.reclaimed(scope, value.id(), gc.token(), now)).isTrue();
    }
    @Test void 到期不依赖扫描才阻止运行且扫描不提前释放配额() {
        for (int i = 0; i < 8; i++) rows.reserve(draft(), limits, now);
        assertThat(rows.cleanupCandidates(now.plusSeconds(3600))).hasSize(8);
        assertThat(rows.reserve(draft(), limits, now)).isFalse();
        var value = rows.cleanupCandidates(now.plusSeconds(3600)).getFirst();
        assertThat(rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now.plusSeconds(3600))).isEmpty();
        var gc = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now.plusSeconds(3600)).orElseThrow();
        assertThat(rows.find(scope, value.id()).orElseThrow().state()).isEqualTo(WorkspaceImport.State.EXPIRED);
        assertThat(rows.reclaimed(scope, value.id(), gc.token(), now.plusSeconds(3600))).isTrue();
        assertThat(rows.reserve(draft(), limits, now)).isTrue();
    }
    private WorkspaceImport ready() {
        var value = draft(); rows.reserve(value, limits, now);
        var upload = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.UPLOAD, Duration.ofMinutes(1), now).orElseThrow();
        var stored = rows.stored(scope, value.id(), value.files().getFirst().id(), 1, upload.token(), now).orElseThrow();
        var ready = rows.complete(scope, value.id(), stored.version(), upload.token(), limits, now).orElseThrow();
        rows.release(scope, value.id(), upload.token()); return ready;
    }

    @Test void 主体字节预算不能绕过且新预算收紧后不发布旧草稿() {
        var small = new WorkspaceImportLimits(4, 16384, 32768, 8, 64, 32768, 32768, Duration.ofHours(1), Duration.ofDays(30));
        var files = List.of(new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 16384, "a".repeat(64)),
                new WorkspaceImportFile(UUID.randomUUID(), "b.txt", 16384, "b".repeat(64)));
        var large = WorkspaceImport.draft(UUID.randomUUID(), scope, "合成目录", files, now, now.plusSeconds(3600));
        assertThat(rows.reserve(large, small, now)).isTrue();
        assertThat(rows.reserve(draft(), small, now)).isFalse();
        var lease = rows.acquire(scope, large.id(), WorkspaceImportLease.Kind.UPLOAD, Duration.ofMinutes(1), now).orElseThrow();
        long version = 1;
        for (var file : files) version = rows.stored(scope, large.id(), file.id(), version, lease.token(), now).orElseThrow().version();
        var tighter = new WorkspaceImportLimits(1, 16384, 16384, 8, 64, 32768, 32768, Duration.ofHours(1), Duration.ofDays(30));
        assertThat(rows.complete(scope, large.id(), version, lease.token(), tighter, now)).isEmpty();
        assertThat(rows.find(scope, large.id()).orElseThrow().state()).isEqualTo(WorkspaceImport.State.DRAFT);
    }

    @Test void 同标识不能替换清单但不同租户可拥有独立命名空间() {
        var first = draft(); assertThat(rows.reserve(first, limits, now)).isTrue();
        var replacement = WorkspaceImport.draft(first.id(), scope, "另一个名称", draft().files(), now, now.plusSeconds(60));
        assertThat(rows.reserve(replacement, limits, now)).isFalse();
        assertThat(rows.find(scope, first.id())).contains(first);
        var other = new WorkspaceImportScope(UUID.randomUUID(), scope.principalId(), scope.agentId());
        var independent = WorkspaceImport.draft(first.id(), other, "其他租户", draft().files(), now, now.plusSeconds(60));
        assertThat(rows.reserve(independent, limits, now)).isTrue();
        assertThat(rows.find(scope, first.id())).contains(first); assertThat(rows.find(other, first.id())).contains(independent);
    }
}
