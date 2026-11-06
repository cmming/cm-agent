package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.DevicePairingSignatures;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.store.InMemoryLocalClientAuthorizationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientPairingServiceTest {
    @Test
    void 浏览器只能确认客户端签名且归属当前主体的配对包() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        var audits = new MemoryAuditRepository();
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        var service = new LocalClientPairingService(repository, repository, new AuditAppender(audits),
                properties, null, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        PrincipalRef principal = new PrincipalRef(UUID.randomUUID(), "owner", "用户", Set.of("agent:run"));
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID requestId = UUID.randomUUID();
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        String publicKey = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(requestId, nonce, "开发机",
                LocalClientDevice.Platform.WINDOWS, publicKey, pair.getPrivate());
        var packageValue = new LocalClientPairingService.PairingPackage(requestId, nonce, "开发机",
                LocalClientDevice.Platform.WINDOWS, publicKey, proof);

        assertThat(service.begin(principal, packageValue).state())
                .isEqualTo(com.cmagent.core.domain.LocalClientPairing.State.PENDING);
        var confirmed = service.confirm(principal, requestId);
        assertThat(confirmed.state()).isEqualTo(com.cmagent.core.domain.LocalClientPairing.State.CONFIRMED);
        assertThat(confirmed.deviceId()).isNotNull();
        assertThat(repository.find(principal.tenantId(), principal.principalId(), confirmed.deviceId())).isPresent();
        assertThat(audits.events).extracting(AuditEvent::eventType)
                .contains("LOCAL_CLIENT_PAIRING_CREATE", "LOCAL_CLIENT_PAIRING_CONFIRM");
    }

    @Test
    void 篡改设备名称的配对包不会登记() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        var service = new LocalClientPairingService(repository, repository,
                new AuditAppender(new MemoryAuditRepository()), properties, null,
                Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID requestId = UUID.randomUUID();
        byte[] nonce = new byte[32];
        String publicKey = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(requestId, nonce, "原名称",
                LocalClientDevice.Platform.LINUX, publicKey, pair.getPrivate());
        var principal = new PrincipalRef(UUID.randomUUID(), "owner", "用户", Set.of("agent:run"));
        assertThatThrownBy(() -> service.begin(principal,
                new LocalClientPairingService.PairingPackage(requestId, nonce, "篡改名称",
                        LocalClientDevice.Platform.LINUX, publicKey, proof)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void 其他主体和其他租户无法确认配对且原主体只能确认一次() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        var service = new LocalClientPairingService(repository, repository,
                new AuditAppender(new MemoryAuditRepository()), new LocalClientProperties(), null,
                Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        UUID tenant = UUID.randomUUID();
        var owner = new PrincipalRef(tenant, "owner", "用户", Set.of("agent:run"));
        var otherOwner = new PrincipalRef(tenant, "other", "用户", Set.of("agent:run"));
        var otherTenant = new PrincipalRef(UUID.randomUUID(), "owner", "用户", Set.of("agent:run"));
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID pairingId = UUID.randomUUID();
        byte[] nonce = new byte[32];
        String publicKey = java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(pairingId, nonce, "开发机",
                LocalClientDevice.Platform.LINUX, publicKey, key.getPrivate());
        service.begin(owner, new LocalClientPairingService.PairingPackage(pairingId, nonce, "开发机",
                LocalClientDevice.Platform.LINUX, publicKey, proof));

        assertThatThrownBy(() -> service.confirm(otherOwner, pairingId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.confirm(otherTenant, pairingId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(service.confirm(owner, pairingId).deviceId()).isNotNull();
        assertThatThrownBy(() -> service.confirm(owner, pairingId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(service.devices(otherOwner)).isEmpty();
        assertThat(service.devices(otherTenant)).isEmpty();
    }

    @Test
    void 配对确认审计失败时新设备必须撤销() throws Exception {
        var repository = new InMemoryLocalClientAuthorizationRepository();
        AuditEventRepository failing = new AuditEventRepository() {
            @Override public void append(AuditEvent event) {
                if ("LOCAL_CLIENT_PAIRING_CONFIRM".equals(event.eventType())) throw new IllegalStateException("模拟审计不可用");
            }
            @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) { return List.of(); }
        };
        var service = new LocalClientPairingService(repository, repository, new AuditAppender(failing),
                new LocalClientProperties(), null, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        var owner = new PrincipalRef(UUID.randomUUID(), "owner", "用户", Set.of("agent:run"));
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID pairingId = UUID.randomUUID();
        byte[] nonce = new byte[32];
        String publicKey = java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(pairingId, nonce, "开发机",
                LocalClientDevice.Platform.LINUX, publicKey, key.getPrivate());
        service.begin(owner, new LocalClientPairingService.PairingPackage(pairingId, nonce, "开发机",
                LocalClientDevice.Platform.LINUX, publicKey, proof));

        assertThatThrownBy(() -> service.confirm(owner, pairingId)).isInstanceOf(RuntimeException.class);
        assertThat(service.devices(owner)).singleElement()
                .satisfies(device -> assertThat(device.state()).isEqualTo(LocalClientDevice.State.REVOKED));
        assertThat(service.status(pairingId).state()).isEqualTo(com.cmagent.core.domain.LocalClientPairing.State.EXPIRED);
    }

    private static final class MemoryAuditRepository implements AuditEventRepository {
        private final List<AuditEvent> events = new ArrayList<>();
        @Override public void append(AuditEvent event) { events.add(event); }
        @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
            return events.stream().filter(event -> event.tenantId().equals(tenantId)).limit(limit).toList();
        }
    }
}
