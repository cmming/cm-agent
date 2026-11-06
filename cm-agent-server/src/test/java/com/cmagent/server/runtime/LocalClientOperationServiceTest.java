package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.store.InMemoryLocalClientAuthorizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClientOperationServiceTest {
    @Test
    void 领取审计失败时内存任务变为未知且不能重派() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), run = UUID.randomUUID(), conversation = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.WINDOWS,
                java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        repository.insertGrant(new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                conversation, run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.READ_ONLY), 1, now, now.plusSeconds(60)));
        var pending = new com.cmagent.core.domain.LocalClientOperation(UUID.randomUUID(), tenant, "owner",
                device.id(), agent, conversation, run, UUID.randomUUID(), UUID.randomUUID().toString(),
                OperationCategory.READ_ONLY, "encrypted", com.cmagent.core.domain.LocalClientOperation.State.PENDING,
                "", "", now, now.plusSeconds(60), null, null, 1);
        repository.insertOperation(pending);
        AuditEventRepository failingAudit = new AuditEventRepository() {
            @Override public void append(AuditEvent event) { throw new IllegalStateException("模拟审计不可用"); }
            @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) { return List.of(); }
        };
        var service = new LocalClientOperationService(repository, repository, new AuditAppender(failingAudit),
                new ModelCredentialCipher(new SecretKeySpec(new byte[32], "AES")), new ObjectMapper(),
                new SensitiveDataRedactor(), new LocalClientProperties(), null, Clock.fixed(now, ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim(device))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.findOperation(tenant, "owner", device.id(), pending.id()).orElseThrow().state())
                .isEqualTo(com.cmagent.core.domain.LocalClientOperation.State.UNKNOWN);
        assertThat(service.claim(device)).isEmpty();
    }

    @Test
    void 排队审计失败时并发领取不能看到任务() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.WINDOWS,
                java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        repository.insertGrant(new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                UUID.randomUUID(), run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.READ_ONLY), 1, now, now.plusSeconds(60)));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AuditEventRepository failingAudit = new AuditEventRepository() {
            @Override public void append(AuditEvent event) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("模拟审计不可用");
            }
            @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) { return List.of(); }
        };
        var service = new LocalClientOperationService(repository, repository, new AuditAppender(failingAudit),
                new ModelCredentialCipher(new SecretKeySpec(new byte[32], "AES")), new ObjectMapper(),
                new SensitiveDataRedactor(), new LocalClientProperties(), null, Clock.fixed(now, ZoneOffset.UTC));
        var request = new ToolInvocationRequest(tenant, agent,
                new PrincipalRef(tenant, "owner", "用户", Set.of()), run, UUID.randomUUID().toString(),
                LocalClientToolCatalog.definitions(tenant).getFirst().id(), "local_client_read_files", "{}");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var queued = executor.submit(() -> service.invoke(request, LocalClientToolCatalog.Kind.READ_FILES));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var claimed = executor.submit(() -> service.claim(device));
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> claimed.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(claimed.get(2, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void 不同Agent不能使用当前运行的设备授权() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.WINDOWS,
                java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        repository.insertGrant(new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner",
                UUID.randomUUID(), UUID.randomUUID(), run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.READ_ONLY), 1, now, now.plusSeconds(60)));
        var service = new LocalClientOperationService(repository, repository,
                new AuditAppender(new MemoryAuditRepository()),
                new ModelCredentialCipher(new SecretKeySpec(new byte[32], "AES")), new ObjectMapper(),
                new SensitiveDataRedactor(), new LocalClientProperties(), null, Clock.fixed(now, ZoneOffset.UTC));
        var request = new ToolInvocationRequest(tenant, UUID.randomUUID(),
                new PrincipalRef(tenant, "owner", "用户", Set.of()), run, UUID.randomUUID().toString(),
                LocalClientToolCatalog.definitions(tenant).getFirst().id(), "local_client_read_files", "{}");

        var result = service.invoke(request, LocalClientToolCatalog.Kind.READ_FILES);

        assertThat(result.success()).isFalse();
        assertThat(service.claim(device)).isEmpty();
    }

    @Test
    void 任务领取与签名回报通过条件版本唤醒原工具调用() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        var audits = new MemoryAuditRepository();
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.WINDOWS,
                java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        repository.insertGrant(new OperationAuthorizationSnapshot(UUID.randomUUID(), tenant, "owner", agent,
                conversation, run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.READ_ONLY), 1, now, now.plusSeconds(60)));
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        properties.setOperationTimeout(java.time.Duration.ofSeconds(2));
        var json = new ObjectMapper();
        var service = new LocalClientOperationService(repository, repository, new AuditAppender(audits),
                new ModelCredentialCipher(new SecretKeySpec(new byte[32], "AES")), json,
                new SensitiveDataRedactor(), properties, null,
                Clock.fixed(now, ZoneOffset.UTC));
        var request = new ToolInvocationRequest(tenant, agent,
                new PrincipalRef(tenant, "owner", "用户", Set.of()),
                run, UUID.randomUUID().toString(), LocalClientToolCatalog.definitions(tenant).getFirst().id(),
                "local_client_read_files",
                "{\"action\":\"read\",\"path\":\"/tmp/test\",\"offset\":0,\"limit\":10}");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() ->
                    service.invoke(request, LocalClientToolCatalog.Kind.READ_FILES));
            LocalClientOperationService.TaskView task = null;
            for (int attempt = 0; attempt < 50 && task == null; attempt++) {
                task = service.claim(device).orElse(null);
                if (task == null) Thread.sleep(10);
            }
            assertThat(task).isNotNull();
            byte[] result = json.writeValueAsBytes(new LocalClientOperationService.ResultPayload(
                    com.cmagent.core.domain.LocalClientOperation.State.SUCCEEDED,
                    task.version(), json.createObjectNode().put("summary", "十字节"), ""));
            service.complete(device, task.operationId(), result);
            var invocation = pending.get(2, TimeUnit.SECONDS);
            assertThat(invocation.success()).isTrue();
            assertThat(invocation.output()).contains("十字节");
            assertThat(invocation.protectedPayload()).isTrue();
        }
        assertThat(audits.events).extracting(AuditEvent::eventType)
                .contains("LOCAL_CLIENT_OPERATION_QUEUE", "LOCAL_CLIENT_OPERATION_DISPATCH",
                        "LOCAL_CLIENT_OPERATION_COMPLETE");
    }

    private static final class MemoryAuditRepository implements AuditEventRepository {
        private final List<AuditEvent> events = new ArrayList<>();
        @Override public void append(AuditEvent event) { events.add(event); }
        @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
            return events.stream().filter(event -> event.tenantId().equals(tenantId)).limit(limit).toList();
        }
    }
}
