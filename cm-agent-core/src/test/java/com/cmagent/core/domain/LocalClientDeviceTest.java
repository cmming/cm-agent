package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientDeviceTest {
    @Test
    void 设备只接受有效的持钥公钥和初始归属() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        Instant now = Instant.parse("2026-11-02T01:00:00Z");
        var device = device("Owner", publicKey, now, now);

        assertThat(device.state()).isEqualTo(LocalClientDevice.State.ACTIVE);
        assertThat(device.toString()).doesNotContain("Owner", publicKey, "工作电脑");
        assertThatThrownBy(() -> device("Owner", "invalid", now, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> device(" ", publicKey, now, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> device("Owner", publicKey, now, now.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 挑战固定随机值与排他期限且不向日志暴露随机值() {
        byte[] nonce = new byte[32];
        Instant now = Instant.parse("2026-11-02T01:00:00Z");
        var challenge = new LocalClientChallenge(UUID.randomUUID(), UUID.randomUUID(), "Owner", UUID.randomUUID(),
                nonce, now, now.plusSeconds(30));
        nonce[0] = 1;
        challenge.nonce()[1] = 1;

        assertThat(challenge.nonce()).containsOnly((byte) 0);
        assertThat(challenge).isEqualTo(new LocalClientChallenge(challenge.id(), challenge.tenantId(),
                challenge.ownerId(), challenge.deviceId(), new byte[32], now, now.plusSeconds(30)));
        assertThat(challenge.toString()).doesNotContain("Owner");
        assertThatThrownBy(() -> new LocalClientChallenge(UUID.randomUUID(), UUID.randomUUID(), "Owner",
                UUID.randomUUID(), new byte[32], now, now)).isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalClientDevice device(String owner, String publicKey, Instant created, Instant updated) {
        return new LocalClientDevice(UUID.randomUUID(), UUID.randomUUID(), owner, "工作电脑",
                LocalClientDevice.Platform.WINDOWS, publicKey, LocalClientDevice.State.ACTIVE, 1, created, updated);
    }
}
