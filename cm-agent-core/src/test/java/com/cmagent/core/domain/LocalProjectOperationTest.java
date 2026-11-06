package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalProjectOperationTest {
    private static LocalProjectOperation operation() {
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        return new LocalProjectOperation(UUID.randomUUID(), UUID.randomUUID(), "owner", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalProjectOperation.Kind.COMMAND,
                "a".repeat(64), "v1:" + java.util.Base64.getEncoder().encodeToString(new byte[12]) + ":"
                    + java.util.Base64.getEncoder().encodeToString(new byte[32]), LocalProjectOperation.State.PROPOSED, 1, now, now);
    }

    @Test void 完整审批派发链只推进状态不替换清单() {
        var initial = operation();
        var next = initial.advance(LocalProjectOperation.State.WAITING_APPROVAL, initial.updatedAt().plusSeconds(1))
                .advance(LocalProjectOperation.State.PREPARED, initial.updatedAt().plusSeconds(2))
                .advance(LocalProjectOperation.State.RUNNING, initial.updatedAt().plusSeconds(3))
                .advance(LocalProjectOperation.State.SUCCEEDED, initial.updatedAt().plusSeconds(4));
        assertThat(next.version()).isEqualTo(5);
        assertThat(next.digest()).isEqualTo(initial.digest());
        assertThat(next.encryptedPayload()).isEqualTo(initial.encryptedPayload());
        assertThat(next.toString()).doesNotContain(initial.encryptedPayload());
        assertThatThrownBy(() -> next.advance(LocalProjectOperation.State.RUNNING, next.updatedAt())).isInstanceOf(IllegalStateException.class);
    }

    @Test void 结果未知只允许核对收口不能再次派发() {
        var initial = operation();
        var unknown = initial.advance(LocalProjectOperation.State.WAITING_APPROVAL, initial.updatedAt())
                .advance(LocalProjectOperation.State.PREPARED, initial.updatedAt())
                .advance(LocalProjectOperation.State.UNKNOWN, initial.updatedAt());
        assertThatThrownBy(() -> unknown.advance(LocalProjectOperation.State.PREPARED, unknown.updatedAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unknown.advance(LocalProjectOperation.State.RUNNING, unknown.updatedAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(unknown.advance(LocalProjectOperation.State.FAILED, unknown.updatedAt()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
    }

    @Test void 未审批不能直接执行且状态时间不能倒退() {
        var initial = operation();
        assertThatThrownBy(() -> initial.advance(LocalProjectOperation.State.RUNNING, initial.updatedAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> initial.advance(LocalProjectOperation.State.WAITING_APPROVAL, initial.updatedAt().minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(initial.advance(LocalProjectOperation.State.CANCELLED, initial.updatedAt()).state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
    }
}

