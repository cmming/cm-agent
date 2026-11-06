package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationCategory;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.store.InMemoryLocalClientAuthorizationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LocalClientAuthorizationServiceTest {
    @Test
    void 授权审计失败期间其他线程不能读取尚未审计的授权() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID(), run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "设备",
                LocalClientDevice.Platform.WINDOWS,
                java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AuditEventRepository failing = new AuditEventRepository() {
            @Override public void append(AuditEvent event) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("模拟审计不可用");
            }
            @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) { return List.of(); }
        };
        var service = new LocalClientAuthorizationService(repository, repository, mock(ToolDefinitionRepository.class),
                new AuditAppender(failing), new LocalClientProperties(), null, Clock.fixed(now, ZoneOffset.UTC));
        var principal = new PrincipalRef(tenant, "owner", "用户", Set.of("agent:run"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creating = executor.submit(() -> service.createForRun(principal, UUID.randomUUID(), UUID.randomUUID(),
                    run, device.id(), OperationAuthorizationMode.FULL_ACCESS, Set.of(OperationCategory.READ_ONLY)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var reading = executor.submit(() -> service.active(principal, run));
            try {
                assertThatThrownBy(() -> reading.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> creating.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(reading.get(2, TimeUnit.SECONDS)).isEmpty();
        }
    }
}
