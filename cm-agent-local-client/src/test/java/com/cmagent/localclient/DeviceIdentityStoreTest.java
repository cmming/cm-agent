package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIdentityStoreTest {
    @TempDir Path directory;

    @Test
    void 系统凭据只保存私钥而元数据不含私钥并可写入配对归属() throws Exception {
        var credentials = new MemoryCredentialStore();
        var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(),
                Optional.of(credentials));
        var created = store.create(URI.create("https://agent.example.test"), "开发机",
                LocalClientDevice.Platform.WINDOWS, new char[0]);
        String metadata = Files.readString(directory.resolve("identity.json"));
        String privateBytes = java.util.Base64.getEncoder()
                .encodeToString(created.keyPair().getPrivate().getEncoded());
        assertThat(metadata).doesNotContain(privateBytes).contains("SYSTEM");

        var loaded = store.load(new char[0]);
        assertThat(loaded.metadata().publicKey()).isEqualTo(created.metadata().publicKey());
        UUID deviceId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        var confirmed = store.confirm(loaded, deviceId, tenantId, "owner");
        assertThat(confirmed.metadata().deviceId()).isEqualTo(deviceId);
        assertThat(store.load(new char[0]).metadata().ownerId()).isEqualTo("owner");
        store.delete(confirmed);
        assertThat(credentials.values).isEmpty();
    }

    @Test
    void 系统凭据不可用时口令文件仍可恢复同一身份() throws Exception {
        var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), Optional.empty());
        char[] password = "test-only-password".toCharArray();
        var created = store.create(URI.create("http://127.0.0.1:8080"), "Linux 主机",
                LocalClientDevice.Platform.LINUX, password);
        var loaded = store.load(password);
        assertThat(loaded.keyPair().getPublic().getEncoded())
                .isEqualTo(created.keyPair().getPublic().getEncoded());
        assertThat(Files.readString(directory.resolve("identity.json"))).contains("PASSWORD");
        Arrays.fill(password, '\0');
    }

    private static final class MemoryCredentialStore implements SystemCredentialStore {
        private final Map<String, byte[]> values = new HashMap<>();
        @Override public String create(byte[] secret) {
            String reference = UUID.randomUUID().toString();
            values.put(reference, secret.clone());
            return reference;
        }
        @Override public Optional<byte[]> read(String reference) {
            byte[] value = values.get(reference);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public boolean delete(String reference) {
            return values.remove(reference) != null;
        }
    }
}
