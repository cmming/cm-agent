package com.cmagent.core.security;

import com.cmagent.core.domain.LocalClientDevice;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePairingSignaturesTest {
    @Test
    void 配对签名绑定请求随机值平台名称和公钥() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID requestId = UUID.randomUUID();
        byte[] nonce = java.util.Arrays.copyOf(
                java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{1}), 32);
        String publicKey = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(requestId, nonce, "工作站", 
                LocalClientDevice.Platform.WINDOWS, publicKey, pair.getPrivate());

        assertThat(DevicePairingSignatures.verify(requestId, nonce, "工作站",
                LocalClientDevice.Platform.WINDOWS, publicKey, proof)).isTrue();
        assertThat(DevicePairingSignatures.verify(UUID.randomUUID(), nonce, "工作站",
                LocalClientDevice.Platform.WINDOWS, publicKey, proof)).isFalse();
        assertThat(DevicePairingSignatures.verify(requestId, nonce, "另一台电脑",
                LocalClientDevice.Platform.WINDOWS, publicKey, proof)).isFalse();
        assertThat(DevicePairingSignatures.verify(requestId, nonce, "工作站",
                LocalClientDevice.Platform.LINUX, publicKey, proof)).isFalse();
    }
}
