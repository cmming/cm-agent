package com.cmagent.core.security;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClientRequestVerifierTest {
    @Test
    void 只有可信设备对应的未过期挑战和实际请求签名能够通过() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-02T01:00:00Z");
        UUID tenant = UUID.randomUUID(), deviceId = UUID.randomUUID();
        var device = new LocalClientDevice(deviceId, tenant, "Owner", "测试设备", LocalClientDevice.Platform.LINUX,
                Base64.getEncoder().encodeToString(key.getPublic().getEncoded()), LocalClientDevice.State.ACTIVE, 1, now, now);
        var challenge = new LocalClientChallenge(UUID.randomUUID(), tenant, "Owner", deviceId,
                new byte[32], now, now.plusSeconds(30));
        byte[] digest = new byte[32];
        var proof = new DeviceRequestProof(challenge.id(), challenge.nonce(), deviceId, tenant, "Owner",
                "server", "POST", "/device/poll", digest);
        byte[] signature = DeviceProofSignatures.sign(proof, key.getPrivate());

        assertThat(LocalClientRequestVerifier.verify(device, challenge, "server", "POST", "/device/poll",
                digest, signature, now)).isTrue();
        assertThat(LocalClientRequestVerifier.verify(device, challenge, "server", "POST", "/device/other",
                digest, signature, now)).isFalse();
        assertThat(LocalClientRequestVerifier.verify(device, challenge, "server", "POST", "/device/poll",
                digest, signature, challenge.expiresAt())).isFalse();
        var otherOwner = new LocalClientChallenge(challenge.id(), tenant, "owner", deviceId,
                challenge.nonce(), now, challenge.expiresAt());
        assertThat(LocalClientRequestVerifier.verify(device, otherOwner, "server", "POST", "/device/poll",
                digest, signature, now)).isFalse();
        var revoked = new LocalClientDevice(deviceId, tenant, "Owner", "测试设备", LocalClientDevice.Platform.LINUX,
                device.publicKey(), LocalClientDevice.State.REVOKED, 2, now, now);
        assertThat(LocalClientRequestVerifier.verify(revoked, challenge, "server", "POST", "/device/poll",
                digest, signature, now)).isFalse();
    }
}
