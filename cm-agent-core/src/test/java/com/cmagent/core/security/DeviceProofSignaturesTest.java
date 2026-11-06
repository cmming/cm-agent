package com.cmagent.core.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceProofSignaturesTest {
    @Test
    void 签名绑定设备租户主体挑战服务端目标与正文摘要() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID challenge = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        byte[] nonce = new byte[32];
        byte[] digest = new byte[32];
        var original = new DeviceRequestProof(challenge, nonce, device, tenant, "owner", "server-a",
                "POST", "/api/device/action", digest);
        byte[] signature = DeviceProofSignatures.sign(original, keys.getPrivate());

        assertThat(DeviceProofSignatures.verify(original, keys.getPublic(), signature)).isTrue();
        var modified = List.of(
                new DeviceRequestProof(UUID.randomUUID(), nonce, device, tenant, "owner", "server-a",
                        "POST", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, UUID.randomUUID(), tenant, "owner", "server-a",
                        "POST", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, device, UUID.randomUUID(), "owner", "server-a",
                        "POST", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, device, tenant, "other", "server-a",
                        "POST", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, device, tenant, "owner", "server-b",
                        "POST", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, device, tenant, "owner", "server-a",
                        "GET", "/api/device/action", digest),
                new DeviceRequestProof(challenge, nonce, device, tenant, "owner", "server-a",
                        "POST", "/api/device/other", digest));
        for (var changed : modified) {
            assertThat(DeviceProofSignatures.verify(changed, keys.getPublic(), signature)).isFalse();
        }
        digest[0] = 1;
        assertThat(DeviceProofSignatures.verify(new DeviceRequestProof(challenge, nonce, device, tenant,
                "owner", "server-a", "POST", "/api/device/action", digest), keys.getPublic(), signature)).isFalse();
        assertThat(DeviceProofSignatures.verify(original, keys.getPublic(), new byte[2])).isFalse();
    }

    @Test
    void 原始数组和返回数组不能改变已经固定的证明() {
        byte[] nonce = new byte[32];
        byte[] digest = new byte[32];
        var proof = new DeviceRequestProof(UUID.randomUUID(), nonce, UUID.randomUUID(), UUID.randomUUID(),
                "owner", "server", "POST", "/path", digest);
        byte[] expected = proof.canonicalBytes();
        nonce[0] = 1;
        digest[0] = 1;
        proof.nonce()[1] = 1;
        proof.bodyDigest()[1] = 1;

        assertThat(proof.canonicalBytes()).isEqualTo(expected);
        assertThat(proof.toString()).doesNotContain("/path", "owner", "server");
        assertThat(proof).isEqualTo(new DeviceRequestProof(proof.challengeId(), proof.nonce(), proof.deviceId(),
                proof.tenantId(), proof.principalId(), proof.audience(), proof.method(), proof.target(), proof.bodyDigest()));
        assertThatThrownBy(() -> new DeviceRequestProof(UUID.randomUUID(), new byte[31], UUID.randomUUID(),
                UUID.randomUUID(), "owner", "server", "POST", "/path", new byte[32]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceRequestProof(UUID.randomUUID(), new byte[32], UUID.randomUUID(),
                UUID.randomUUID(), "owner", "server", "POST", "/\uD800", new byte[32]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
