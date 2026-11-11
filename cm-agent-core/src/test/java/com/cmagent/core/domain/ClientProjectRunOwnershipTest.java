package com.cmagent.core.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectRunOwnershipTest {
    private final UUID tenant = UUID.randomUUID(), run = UUID.randomUUID(), instance = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-10T00:00:00Z");
    private final ClientProjectRunOwnership active = new ClientProjectRunOwnership(
            tenant, run, "owner", instance, 3, ClientProjectRunOwnership.Phase.EXECUTING, now.plusSeconds(20));

    @Test void 有效执行许可必须同时匹配实例代次和数据库期限() {
        assertThat(active.permitsExecution(instance, 3, now)).isTrue();
        assertThat(active.permitsExecution(UUID.randomUUID(), 3, now)).isFalse();
        assertThat(active.permitsExecution(instance, 2, now)).isFalse();
        assertThat(active.permitsExecution(instance, 3, now.plusSeconds(20))).isFalse();
        assertThat(active.permitsRecovery(instance, 3, now)).isFalse();
    }

    @Test void 只有到期后才能以新代次进入受限恢复且旧代次失效() {
        UUID replacement = UUID.randomUUID();
        assertThatThrownBy(() -> active.takeOver(replacement, now.plusSeconds(19), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        var recovered = active.takeOver(replacement, now.plusSeconds(20), Duration.ofSeconds(30));
        assertThat(recovered.epoch()).isEqualTo(4);
        assertThat(recovered.permitsRecovery(replacement, 4, now.plusSeconds(20))).isTrue();
        assertThat(recovered.permitsExecution(replacement, 4, now.plusSeconds(20))).isFalse();
        assertThat(recovered.permitsRecovery(instance, 3, now.plusSeconds(20))).isFalse();
        assertThat(recovered.tenantId()).isEqualTo(tenant);
        assertThat(recovered.runId()).isEqualTo(run);
        assertThat(recovered.ownerId()).isEqualTo("owner");
    }

    @Test void 过期关闭错实例和退回期限都不能续期() {
        assertThat(active.renew(instance, 3, now.plusSeconds(10), Duration.ofSeconds(30)).expiresAt())
                .isEqualTo(now.plusSeconds(40));
        assertThatThrownBy(() -> active.renew(instance, 3, now, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active.renew(instance, 3, now.plusSeconds(20), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active.renew(UUID.randomUUID(), 3, now, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        var closed = new ClientProjectRunOwnership(tenant, run, "owner", instance, 3,
                ClientProjectRunOwnership.Phase.CLOSED, now.minusSeconds(1));
        assertThatThrownBy(() -> closed.takeOver(UUID.randomUUID(), now, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void 恢复代次不能回绕且租期必须有界() {
        var highest = new ClientProjectRunOwnership(tenant, run, "owner", instance, Long.MAX_VALUE,
                ClientProjectRunOwnership.Phase.EXECUTING, now);
        assertThatThrownBy(() -> highest.takeOver(UUID.randomUUID(), now, Duration.ofSeconds(30)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> active.takeOver(UUID.randomUUID(), now.plusSeconds(20), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> active.takeOver(UUID.randomUUID(), now.plusSeconds(20), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> active.takeOver(UUID.randomUUID(), now.plusSeconds(20), Duration.ofMinutes(6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> active.takeOver(instance, now.plusSeconds(20), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(active.toString()).doesNotContain("owner");
    }
}
