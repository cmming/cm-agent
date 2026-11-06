package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProjectRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.KeyGenerator;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LocalProjectRootCipherTest {
    @TempDir Path temporary;

    @Test void 信封同时认证租户主体根编号节点和原生身份() throws Exception {
        var owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试", Set.of());
        var cipher = cipher("node-1");
        var root = new LocalProjectDirectoryTickets.Root(temporary.toAbsolutePath().normalize(), "native-identity");
        var stored = cipher.seal(owner, UUID.randomUUID(), root, Instant.now());
        assertEquals(root, cipher.open(owner, stored));
        assertFalse(stored.encryptedDirectory().contains(temporary.toString()));
        assertFalse(stored.toString().contains(temporary.toString()));
        assertThrows(IllegalStateException.class, () -> cipher.open(new PrincipalRef(owner.tenantId(), "owner", "测试", Set.of()), stored));
        assertThrows(IllegalStateException.class, () -> cipher.open(new PrincipalRef(UUID.randomUUID(), owner.principalId(), "测试", Set.of()), stored));
        var copied = new LocalProjectRoot(UUID.randomUUID(), stored.tenantId(), stored.ownerId(), stored.nodeId(), stored.encryptedDirectory(), stored.identity(), stored.createdAt());
        assertThrows(IllegalStateException.class, () -> cipher.open(owner, copied));
        var changedIdentity = new LocalProjectRoot(stored.id(), stored.tenantId(), stored.ownerId(), stored.nodeId(), stored.encryptedDirectory(), "0".repeat(64), stored.createdAt());
        assertThrows(IllegalStateException.class, () -> cipher.open(owner, changedIdentity));
        var otherNode = new LocalProjectRoot(stored.id(), stored.tenantId(), stored.ownerId(), "node-2", stored.encryptedDirectory(), stored.identity(), stored.createdAt());
        assertThrows(IllegalStateException.class, () -> cipher.open(owner, otherNode));
    }

    @Test void 密钥变更或认证标签损坏只返回安全原因() throws Exception {
        var owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试", Set.of());
        var first = cipher("node");
        var stored = first.seal(owner, UUID.randomUUID(), new LocalProjectDirectoryTickets.Root(temporary.toAbsolutePath(), "identity"), Instant.now());
        var failure = assertThrows(IllegalStateException.class, () -> cipher("node").open(owner, stored));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(temporary.toString()));
        String encrypted = stored.encryptedDirectory();
        int index = encrypted.lastIndexOf(':') + 1;
        String damaged = encrypted.substring(0, index) + (encrypted.charAt(index) == 'A' ? 'B' : 'A') + encrypted.substring(index + 1);
        var tampered = new LocalProjectRoot(stored.id(), stored.tenantId(), stored.ownerId(), stored.nodeId(), damaged, stored.identity(), stored.createdAt());
        assertThrows(IllegalStateException.class, () -> first.open(owner, tampered));
    }

    private static LocalProjectRootCipher cipher(String node) throws Exception {
        var generator = KeyGenerator.getInstance("AES"); generator.init(256);
        return new LocalProjectRootCipher(new ModelCredentialCipher(generator.generateKey()), new ObjectMapper(), node);
    }
}
