package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.DeviceProofSignatures;
import com.cmagent.core.security.DeviceRequestProof;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.store.InMemoryLocalClientAuthorizationRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientProofServiceTest {
    @Test
    void 请求签名绑定方法路径和正文且挑战只消费一次() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        properties.setAudience("https://agent.example.test");
        var service = new LocalClientProofService(repository, properties,
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));
        var challenge = service.challenge(device.id(), "127.0.0.1");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var proof = new DeviceRequestProof(challenge.challengeId(), Base64.getDecoder().decode(challenge.nonce()),
                device.id(), tenant, "owner", properties.getAudience(), "POST",
                "/api/local-client/bootstrap/operations/next",
                MessageDigest.getInstance("SHA-256").digest(body));
        byte[] signature = DeviceProofSignatures.sign(proof, pair.getPrivate());

        assertThat(service.verifyAndConsume(device.id(), challenge.challengeId(),
                Base64.getEncoder().encodeToString(signature), "POST",
                "/api/local-client/bootstrap/operations/next", body, "127.0.0.1").id())
                .isEqualTo(device.id());
        assertThatThrownBy(() -> service.verifyAndConsume(device.id(), challenge.challengeId(),
                Base64.getEncoder().encodeToString(signature), "POST",
                "/api/local-client/bootstrap/operations/next", body, "127.0.0.1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void 改写正文或路径不能复用签名() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var repository = new InMemoryLocalClientAuthorizationRepository();
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "owner", "开发机",
                LocalClientDevice.Platform.LINUX,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        repository.insert(device);
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        properties.setAudience("https://agent.example.test");
        var service = new LocalClientProofService(repository, properties,
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));
        var challenge = service.challenge(device.id(), "127.0.0.1");
        byte[] body = new byte[]{1};
        var proof = new DeviceRequestProof(challenge.challengeId(), Base64.getDecoder().decode(challenge.nonce()),
                device.id(), tenant, "owner", properties.getAudience(), "POST", "/path-a",
                MessageDigest.getInstance("SHA-256").digest(body));
        byte[] signature = DeviceProofSignatures.sign(proof, pair.getPrivate());

        assertThatThrownBy(() -> service.verifyAndConsume(device.id(), challenge.challengeId(),
                Base64.getEncoder().encodeToString(signature), "POST", "/path-b", body, "127.0.0.1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.verifyAndConsume(device.id(), challenge.challengeId(),
                Base64.getEncoder().encodeToString(signature), "POST", "/path-a", new byte[]{2}, "127.0.0.1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
