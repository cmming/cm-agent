package com.cmagent.server.store;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLocalClientAuthorizationRepositoryTest {
    @Test
    void 非所属主体撤销失败且不影响原授权() {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID(), run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var device = device(tenant, "owner", now);
        repository.insert(device);
        var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", UUID.randomUUID(),
                UUID.randomUUID(), run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.NETWORK), 1, now, now.plusSeconds(60));
        repository.insertGrant(grant);

        assertThat(repository.revokeGrant(tenant, "other", grant.authorizationId(), now)).isFalse();
        assertThat(repository.findActiveGrantByRun(tenant, "owner", run, now)).contains(grant);
    }

    @Test
    void 并发撤销和领取只能按单一顺序生效() throws Exception {
        for (int i = 0; i < 100; i++) {
            var repository = new InMemoryLocalClientAuthorizationRepository();
            UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), conversation = UUID.randomUUID(), run = UUID.randomUUID();
            Instant now = Instant.parse("2026-11-02T00:00:00Z");
            var device = device(tenant, "owner", now);
            repository.insert(device);
            var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                    conversation, run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                    Set.of(OperationCategory.NETWORK), 1, now, now.plusSeconds(60));
            repository.insertGrant(grant);
            repository.insertOperation(operation(tenant, agent, conversation, run, device.id(), now));
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var revoked = executor.submit(() -> { start.await(); return repository.revokeGrant(tenant, "owner", grant.authorizationId(), now); });
                var claimed = executor.submit(() -> { start.await(); return repository.claimNextOperation(tenant, "owner", device.id(), now); });
                start.countDown();
                assertThat(revoked.get()).isTrue();
                claimed.get();
                assertThat(repository.claimNextOperation(tenant, "owner", device.id(), now)).isEmpty();
                assertThat(repository.findActiveGrantByRun(tenant, "owner", run, now)).isEmpty();
            }
        }
    }

    @Test
    void 挑战单次消费且设备撤销后不再派发任务() {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var device = device(tenant, "owner", now);
        assertThat(repository.insert(device)).isTrue();
        assertThat(repository.findAnyById(device.id())).contains(device);
        assertThat(repository.listByOwner(tenant, "owner")).containsExactly(device);

        var challenge = new LocalClientChallenge(UUID.randomUUID(), tenant, "owner", device.id(),
                new byte[32], now, now.plusSeconds(30));
        assertThat(repository.insertChallenge(challenge)).isTrue();
        assertThat(repository.findChallenge(tenant, "owner", device.id(), challenge.id(), now)).contains(challenge);
        assertThat(repository.consumeChallenge(tenant, "owner", device.id(), challenge.id(), now)).isTrue();
        assertThat(repository.consumeChallenge(tenant, "owner", device.id(), challenge.id(), now)).isFalse();

        var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                conversation, run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.COMMAND), 1, now, now.plusSeconds(3600));
        assertThat(repository.insertGrant(grant)).isTrue();
        var operation = operation(tenant, agent, conversation, run, device.id(), now);
        assertThat(repository.insertOperation(operation)).isTrue();
        assertThat(repository.revoke(tenant, "owner", device.id(), device.version(), now.plusSeconds(1))).isTrue();
        assertThat(repository.claimNextOperation(tenant, "owner", device.id(), now.plusSeconds(2))).isEmpty();
    }

    @Test
    void 同一任务只允许领取一次并条件收口() {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var device = device(tenant, "owner", now);
        repository.insert(device);
        repository.insertGrant(new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                conversation, run, device.id(), OperationAuthorizationMode.ASK_ON_EFFECT,
                Set.of(OperationCategory.NETWORK), 1, now, now.plusSeconds(3600)));
        repository.insertOperation(operation(tenant, agent, conversation, run, device.id(), now));

        var claimed = repository.claimNextOperation(tenant, "owner", device.id(), now.plusSeconds(1)).orElseThrow();
        assertThat(claimed.state()).isEqualTo(LocalClientOperation.State.DISPATCHED);
        assertThat(repository.claimNextOperation(tenant, "owner", device.id(), now.plusSeconds(2))).isEmpty();
        assertThat(repository.completeOperation(claimed.id(), tenant, "owner", device.id(),
                LocalClientOperation.State.SUCCEEDED, "result", "", claimed.version(), now.plusSeconds(3))).isTrue();
        assertThat(repository.completeOperation(claimed.id(), tenant, "owner", device.id(),
                LocalClientOperation.State.FAILED, "", "重复", claimed.version(), now.plusSeconds(4))).isFalse();
    }

    private static LocalClientDevice device(UUID tenant, String owner, Instant now) {
        try {
            var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new LocalClientDevice(UUID.randomUUID(), tenant, owner, "测试设备",
                    LocalClientDevice.Platform.WINDOWS,
                    java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    LocalClientDevice.State.ACTIVE, 1, now, now);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static LocalClientOperation operation(
            UUID tenant, UUID agent, UUID conversation, UUID run, UUID device, Instant now) {
        return new LocalClientOperation(UUID.randomUUID(), tenant, "owner", device, agent,
                conversation, run, UUID.randomUUID(), UUID.randomUUID().toString(),
                OperationCategory.NETWORK, "ciphertext", LocalClientOperation.State.PENDING,
                "", "", now, now.plusSeconds(60), null, null, 1);
    }
}
