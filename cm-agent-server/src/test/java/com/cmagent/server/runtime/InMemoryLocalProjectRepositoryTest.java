package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.server.store.InMemoryLocalProjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InMemoryLocalProjectRepositoryTest {
    private static LocalProject project(UUID tenant, String owner, String name, UUID id) {
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        return new LocalProject(id, tenant, owner, name, "node", UUID.randomUUID(), "a".repeat(64),
                LocalProject.State.ACTIVE, 1, now, now);
    }

    @Test void 项目按租户和精确主体隔离且重复ID不能覆盖() {
        var repo = new InMemoryLocalProjectRepository();
        var item = project(UUID.randomUUID(), "Owner", "项目", UUID.randomUUID());
        assertThat(repo.insert(item)).isTrue();
        assertThat(repo.insert(item)).isFalse();
        assertThat(repo.find(item.tenantId(), "owner", item.id())).isEmpty();
        assertThat(repo.find(item.tenantId(), "Owner ", item.id())).isEmpty();
        assertThat(repo.find(UUID.randomUUID(), "Owner", item.id())).isEmpty();
        assertThat(repo.find(item.tenantId(), "Owner", item.id())).contains(item);
    }

    @Test void 分页前过滤owner并使用稳定复合游标() {
        var repo = new InMemoryLocalProjectRepository();
        UUID tenant = UUID.randomUUID();
        var mine = project(tenant, "me", "我的", UUID.fromString("00000000-0000-0000-0000-000000000002"));
        var earlier = project(tenant, "me", "更早", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var hidden = project(tenant, "other", "其他主体", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        repo.insert(mine); repo.insert(earlier); repo.insert(hidden);
        assertThat(repo.list(tenant, "me", new LocalProjectPageRequest(1, null, null), false)).containsExactly(mine);
        assertThat(repo.list(tenant, "me", new LocalProjectPageRequest(1, mine.updatedAt(), mine.id()), false)).containsExactly(earlier);
        assertThat(repo.list(tenant, "ME", new LocalProjectPageRequest(50, null, null), true)).isEmpty();
    }

    @Test void 条件改名和归档不换根且旧版本不能继续写() {
        var repo = new InMemoryLocalProjectRepository();
        var original = project(UUID.randomUUID(), "owner", "项目", UUID.randomUUID());
        repo.insert(original);
        assertThat(repo.rename(original.tenantId(), "other", original.id(), 1, "不允许", original.createdAt())).isFalse();
        assertThat(repo.rename(original.tenantId(), "owner", original.id(), 1, "改名", original.createdAt().plusSeconds(1))).isTrue();
        assertThat(repo.rename(original.tenantId(), "owner", original.id(), 1, "旧版本", original.createdAt().plusSeconds(2))).isFalse();
        var renamed = repo.find(original.tenantId(), "owner", original.id()).orElseThrow();
        assertThat(renamed.rootBindingId()).isEqualTo(original.rootBindingId());
        assertThat(renamed.rootIdentity()).isEqualTo(original.rootIdentity());
        assertThat(repo.archive(original.tenantId(), "owner", original.id(), 2, original.createdAt().plusSeconds(2))).isTrue();
        assertThat(repo.list(original.tenantId(), "owner", new LocalProjectPageRequest(50, null, null), false)).isEmpty();
        assertThat(repo.list(original.tenantId(), "owner", new LocalProjectPageRequest(50, null, null), true)).hasSize(1);
        assertThat(repo.rename(original.tenantId(), "owner", original.id(), 3, "已归档", original.createdAt().plusSeconds(3))).isFalse();
    }

    @Test void 并发相同版本更新只有一次成功() throws Exception {
        var repo = new InMemoryLocalProjectRepository();
        var original = project(UUID.randomUUID(), "owner", "项目", UUID.randomUUID());
        repo.insert(original);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var outcomes = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 32; i++) outcomes.add(pool.submit(() -> repo.rename(
                    original.tenantId(), "owner", original.id(), 1, "改名", original.createdAt().plusSeconds(1))));
            int accepted = 0;
            for (var outcome : outcomes) if (outcome.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(repo.find(original.tenantId(), "owner", original.id()).orElseThrow().version()).isEqualTo(2);
        }
    }
}

