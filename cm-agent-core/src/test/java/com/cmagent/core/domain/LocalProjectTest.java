package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalProjectTest {
    private static LocalProject project() {
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        return new LocalProject(UUID.randomUUID(), UUID.randomUUID(), "Owner", "测试项目", "local-node",
                UUID.randomUUID(), "a".repeat(64), LocalProject.State.ACTIVE, 1, now, now);
    }

    @Test void 重命名只推进元数据版本不改变项目根和归属() {
        var original = project();
        var renamed = original.rename("新名称", original.createdAt().plusSeconds(1));
        assertThat(renamed.name()).isEqualTo("新名称");
        assertThat(renamed.version()).isEqualTo(2);
        assertThat(renamed.id()).isEqualTo(original.id());
        assertThat(renamed.tenantId()).isEqualTo(original.tenantId());
        assertThat(renamed.ownerId()).isEqualTo("Owner");
        assertThat(renamed.rootBindingId()).isEqualTo(original.rootBindingId());
        assertThat(renamed.rootIdentity()).isEqualTo(original.rootIdentity());
        assertThat(renamed.nodeId()).isEqualTo(original.nodeId());
        assertThat(renamed.createdAt()).isEqualTo(original.createdAt());
    }

    @Test void 归档幂等且不能继续重命名() {
        var original = project();
        var archived = original.archive(original.createdAt().plusSeconds(1));
        assertThat(archived.state()).isEqualTo(LocalProject.State.ARCHIVED);
        assertThat(archived.version()).isEqualTo(2);
        assertThat(archived.archive(original.createdAt().plusSeconds(2))).isSameAs(archived);
        assertThatThrownBy(() -> archived.rename("改名", archived.updatedAt())).isInstanceOf(IllegalStateException.class);
    }

    @Test void 拒绝不合法摘要展示名版本和时间倒退() {
        var original = project();
        assertThatThrownBy(() -> original.rename(" ", original.updatedAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.rename("a".repeat(121), original.updatedAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.rename("合法", original.createdAt().minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProject(original.id(), original.tenantId(), "Owner", "项目", "node",
                original.rootBindingId(), "非摘要", LocalProject.State.ACTIVE, 1, original.createdAt(), original.updatedAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 项目游标必须成对并限制页大小() {
        assertThat(new LocalProjectPageRequest(50, null, null).limit()).isEqualTo(50);
        assertThatThrownBy(() -> new LocalProjectPageRequest(0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProjectPageRequest(101, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProjectPageRequest(50, Instant.now(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProjectPageRequest(50, null, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}

