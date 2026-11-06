package com.cmagent.core.security;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationAuthorizationSnapshotTest {
    private static final Instant GRANTED = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant EXPIRES = GRANTED.plusSeconds(60);

    @Test
    void 固定能力集合并按服务端时间判断排他过期边界() {
        var categories = new HashSet<>(Set.of(OperationCategory.READ_ONLY));
        var snapshot = snapshot(categories, 1, GRANTED, EXPIRES);
        categories.add(OperationCategory.COMMAND);

        assertThat(snapshot.categories()).containsExactly(OperationCategory.READ_ONLY);
        assertThat(snapshot.isWithinTimeWindow(GRANTED.minusNanos(1))).isFalse();
        assertThat(snapshot.isWithinTimeWindow(GRANTED)).isTrue();
        assertThat(snapshot.isWithinTimeWindow(EXPIRES.minusNanos(1))).isTrue();
        assertThat(snapshot.isWithinTimeWindow(EXPIRES)).isFalse();
        assertThatThrownBy(() -> snapshot.categories().add(OperationCategory.COMMAND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 拒绝空能力无效策略版本和非递增时间窗口() {
        assertThatThrownBy(() -> snapshot(Set.of(), 1, GRANTED, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Set.of(OperationCategory.READ_ONLY), 0, GRANTED, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Set.of(OperationCategory.READ_ONLY), 1, GRANTED, GRANTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OperationAuthorizationSnapshot snapshot(
            Set<OperationCategory> categories, long version, Instant granted, Instant expires) {
        return new OperationAuthorizationSnapshot(UUID.randomUUID(), UUID.randomUUID(), "owner",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OperationAuthorizationMode.FULL_ACCESS, categories, version, granted, expires);
    }
}
