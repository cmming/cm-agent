package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.RuntimeCheckpoint;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientProjectReadSnapshotsTest {
    @Test void 数据库微秒往返仍可核验快照但根身份变化被拒绝() throws Exception {
        Instant now = Instant.parse("2026-11-10T00:00:00.123456789Z");
        var original = new ClientProjectBinding(UUID.randomUUID(), "owner", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 1, now);
        var principal = new PrincipalRef(original.tenantId(), "owner", "测试", Set.of());
        var checkpoints = mock(RuntimeCheckpointRepository.class);
        var stored = new AtomicReference<RuntimeCheckpoint>();
        when(checkpoints.find(any(), any(), any(), any())).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(checkpoints.save(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return stored.get(); });
        var generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        var snapshots = new ClientProjectReadSnapshots(mock(ClientProjectService.class), checkpoints,
                new ModelCredentialCipher(generator.generateKey()), new SensitiveDataRedactor(),
                new ObjectMapper().findAndRegisterModules(), new ClientProjectProperties(),
                Clock.fixed(now, ZoneOffset.UTC));
        snapshots.initialize(principal, original);
        var roundTrip = copy(original, original.rootIdentity(), Instant.parse("2026-11-10T00:00:00.123456Z"));

        snapshots.verify(principal, roundTrip);

        assertThat(original).isEqualTo(roundTrip);
        assertThatThrownBy(() -> snapshots.verify(principal, copy(original, "b".repeat(64), roundTrip.createdAt())))
                .isInstanceOf(IllegalStateException.class).hasMessage("客户端项目读取快照不可核实");
    }

    private ClientProjectBinding copy(ClientProjectBinding source, String identity, Instant created) {
        return new ClientProjectBinding(source.tenantId(), source.ownerId(), source.projectId(),
                source.agentId(), source.conversationId(), source.runId(), source.deviceId(),
                source.clientRootId(), identity, source.projectVersion(), created);
    }
}
