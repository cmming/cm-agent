package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectCommandTest {
    @Test void 命令必须经排队和运行才能以退出证据收口() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        ClientProjectCommand proposed = command(now);
        ClientProjectCommand queued = proposed.update(ClientProjectCommand.State.QUEUED,
                false, null, false, false, "", 0, now.plusSeconds(1));
        ClientProjectCommand running = queued.update(ClientProjectCommand.State.RUNNING,
                false, null, false, false, "", 0, now.plusSeconds(2));
        ClientProjectCommand succeeded = running.update(ClientProjectCommand.State.SUCCEEDED,
                false, 0, true, true, "encrypted-output", 3, now.plusSeconds(3));

        assertThat(succeeded.state()).isEqualTo(ClientProjectCommand.State.SUCCEEDED);
        assertThat(succeeded.exitCode()).isZero();
        assertThat(succeeded.version()).isEqualTo(4);
        assertThatThrownBy(() -> succeeded.update(ClientProjectCommand.State.RUNNING, false,
                null, false, false, "", 3, now.plusSeconds(4)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void 成功状态必须绑定零退出码() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        ClientProjectCommand proposed = command(now);
        ClientProjectCommand queued = proposed.update(ClientProjectCommand.State.QUEUED,
                false, null, false, false, "", 0, now.plusSeconds(1));
        ClientProjectCommand running = queued.update(ClientProjectCommand.State.RUNNING,
                false, null, false, false, "", 0, now.plusSeconds(2));
        assertThatThrownBy(() -> running.update(ClientProjectCommand.State.SUCCEEDED,
                false, 2, false, false, "", 1, now.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ClientProjectCommand command(Instant now) {
        return new ClientProjectCommand(UUID.randomUUID(), UUID.randomUUID(), "owner",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64), true, "encrypted-plan", "", false,
                false, null, ClientProjectCommand.State.PROPOSED, false, 0, 1, now, now);
    }
}
