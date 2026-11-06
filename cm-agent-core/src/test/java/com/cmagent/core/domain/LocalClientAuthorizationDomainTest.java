package com.cmagent.core.domain;

import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientAuthorizationDomainTest {
    @Test
    void 单轮授权拒绝空能力且快照不能被外部扩大() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OperationAuthorizationMode.FULL_ACCESS, Set.of(), 1,
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T01:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);

        var categories = new java.util.HashSet<>(Set.of(OperationCategory.COMMAND));
        var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OperationAuthorizationMode.FULL_ACCESS, categories, 1,
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T01:00:00Z"));
        categories.add(OperationCategory.NETWORK);
        assertThat(grant.categories()).containsExactly(OperationCategory.COMMAND);
    }

    @Test
    void 未派发取消不需要伪造设备取得时间() {
        var operation = new LocalClientOperation(UUID.randomUUID(), UUID.randomUUID(), "owner",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "call", OperationCategory.COMMAND, "ciphertext",
                LocalClientOperation.State.CANCELLED, "", "取消",
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T00:01:00Z"),
                null, Instant.parse("2026-11-02T00:00:10Z"), 2);
        assertThat(operation.dispatchedAt()).isNull();
    }
}
