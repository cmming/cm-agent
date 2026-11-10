package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectOperationTest {
    @Test void 文件变更只能从提案取得一次应用许可() {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        ClientProjectOperation proposed = new ClientProjectOperation(UUID.randomUUID(),
                UUID.randomUUID(), "owner", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, "a".repeat(64),
                "b".repeat(64), true, "encrypted", ClientProjectOperation.State.PROPOSED,
                1, now, now);

        ClientProjectOperation applying = proposed.advance(ClientProjectOperation.State.APPLYING,
                now.plusSeconds(1));
        ClientProjectOperation succeeded = applying.advance(
                ClientProjectOperation.State.SUCCEEDED, now.plusSeconds(2));

        assertThat(succeeded.state()).isEqualTo(ClientProjectOperation.State.SUCCEEDED);
        assertThat(succeeded.version()).isEqualTo(3);
        assertThatThrownBy(() -> succeeded.advance(ClientProjectOperation.State.PROPOSED,
                now.plusSeconds(3))).isInstanceOf(IllegalStateException.class);
    }
}
