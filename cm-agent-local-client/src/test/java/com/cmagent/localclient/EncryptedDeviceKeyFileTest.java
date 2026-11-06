package com.cmagent.localclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedDeviceKeyFileTest {
    @TempDir Path directory;

    @Test
    void 设备密钥加密落盘后可用原口令恢复且不覆盖已有文件() throws Exception {
        var original = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        char[] password = "test-only-password".toCharArray();
        Path file = directory.resolve("device.key");
        var store = new EncryptedDeviceKeyFile(new SecureRandom());
        store.create(file, original, password);

        byte[] disk = Files.readAllBytes(file);
        assertThat(indexOf(disk, original.getPrivate().getEncoded())).isEqualTo(-1);
        var restored = store.unlock(file, password);
        assertThat(restored.getPublic().getEncoded()).isEqualTo(original.getPublic().getEncoded());
        assertThat(restored.getPrivate().getEncoded()).isEqualTo(original.getPrivate().getEncoded());
        assertThatThrownBy(() -> store.create(file, original, password)).isInstanceOf(java.io.IOException.class);
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void 错误口令或篡改密文不能解锁身份() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path file = directory.resolve("tampered.key");
        var store = new EncryptedDeviceKeyFile(new SecureRandom());
        store.create(file, key, "test-only-password".toCharArray());
        assertThatThrownBy(() -> store.unlock(file, "wrong-password".toCharArray()))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        byte[] encoded = Files.readAllBytes(file);
        encoded[encoded.length - 1] ^= 1;
        Files.write(file, encoded);
        assertThatThrownBy(() -> store.unlock(file, "test-only-password".toCharArray()))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
