package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceImportStorageTest {
    /** 独立合成私有目录；不使用部署卷或真实用户资料。 */
    @TempDir Path root;
    /** 每次测试随机密钥，只在内存与子进程环境中传递。 */
    private final String key = key();
    private static String key() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
    private static WorkspaceImport batch(byte[] bytes) throws Exception {
        var now = Instant.now();
        return WorkspaceImport.draft(UUID.randomUUID(), new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID()), "合成目录",
                List.of(new WorkspaceImportFile(UUID.randomUUID(), "资料.txt", bytes.length,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))), now, now.plusSeconds(60));
    }
    @Test void 密文不可覆盖且新存储实例可恢复() throws Exception {
        byte[] bytes = "合成参考资料".getBytes(java.nio.charset.StandardCharsets.UTF_8); var batch = batch(bytes); var file = batch.files().getFirst();
        var storage = new FileSystemWorkspaceImportStorage(root, key); storage.write(batch, file, new ByteArrayInputStream(bytes));
        assertThat(new FileSystemWorkspaceImportStorage(root, key).readVerified(batch, file)).isEqualTo(bytes);
        assertThatThrownBy(() -> storage.write(batch, file, new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class);
        try (var paths = Files.walk(root)) {
            var object = paths.filter(p -> p.toString().endsWith(".gcm")).findFirst().orElseThrow();
            var cipher = Files.readAllBytes(object); assertThat(cipher).hasSize(bytes.length + 32);
            assertThat(HexFormat.of().formatHex(cipher)).doesNotContain(HexFormat.of().formatHex(bytes));
            var posix = Files.getFileAttributeView(object, java.nio.file.attribute.PosixFileAttributeView.class);
            if (posix != null) {
                assertThat(Files.getPosixFilePermissions(root)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                assertThat(Files.getPosixFilePermissions(object)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } else {
                var acl = Files.getFileAttributeView(root, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
                assertThat(acl).hasSize(1); assertThat(acl.getFirst().principal()).isEqualTo(Files.getOwner(root));
            }
            cipher[cipher.length - 1] ^= 1; Files.write(object, cipher);
        }
        assertThatThrownBy(() -> storage.readVerified(batch, file)).isInstanceOf(IOException.class).hasMessage("导入密文缺失或完整性验证失败");
    }
    @Test void 读取实际有界且非法正文与摘要拒绝时不发布对象() throws Exception {
        var storage = new FileSystemWorkspaceImportStorage(root, key); var batch = batch(new byte[]{'a'}); var file = batch.files().getFirst();
        var input = new ByteArrayInputStream(new byte[1000]);
        assertThatThrownBy(() -> storage.write(batch, file, input)).isInstanceOf(FileSystemWorkspaceImportStorage.ContentFailure.class);
        assertThat(input.available()).isEqualTo(998);
        assertThatThrownBy(() -> storage.write(batch, file, new ByteArrayInputStream(new byte[]{'b'})))
                .isInstanceOf(FileSystemWorkspaceImportStorage.ContentFailure.class).hasMessage("文件实际长度或摘要与确认清单不一致，请重新选择");
        for (byte[] bytes : List.of(new byte[]{0}, new byte[]{(byte) 0xff}, new byte[]{' '})) {
            var invalid = batch(bytes);
            assertThatThrownBy(() -> storage.write(invalid, invalid.files().getFirst(), new ByteArrayInputStream(bytes)))
                    .isInstanceOf(FileSystemWorkspaceImportStorage.ContentFailure.class);
        }
        try (var files = Files.walk(root)) { assertThat(files.filter(p -> p.toString().endsWith(".gcm")).count()).isZero(); }
    }
    @Test void 主体或清单错配及密钥丢失不能读取() throws Exception {
        byte[] bytes = {'a'}; var batch = batch(bytes); var file = batch.files().getFirst();
        var storage = new FileSystemWorkspaceImportStorage(root, key); storage.write(batch, file, new ByteArrayInputStream(bytes));
        var scope = new WorkspaceImportScope(batch.scope().tenantId(), "other", batch.scope().agentId());
        var other = WorkspaceImport.draft(batch.id(), scope, batch.displayName(), batch.files(), batch.createdAt(), batch.expiresAt());
        assertThatThrownBy(() -> storage.readVerified(other, file)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new FileSystemWorkspaceImportStorage(root, key()).readVerified(batch, file)).isInstanceOf(IOException.class);
    }
    @Test void 崩溃暂存回收可重跑并阻止迟到写入() throws Exception {
        byte[] bytes = {'a'}; var batch = batch(bytes); var file = batch.files().getFirst();
        var storage = new FileSystemWorkspaceImportStorage(root, key); storage.write(batch, file, new ByteArrayInputStream(bytes));
        var directory = root.resolve(batch.scope().tenantId().toString()).resolve(batch.id().toString());
        Files.write(directory.resolve(file.id() + ".stage"), new byte[3]);
        storage.delete(batch); storage.delete(batch);
        assertThat(Files.exists(directory.resolve(file.id() + ".stage"))).isFalse();
        assertThat(Files.exists(directory.resolve(file.id() + ".gcm"))).isFalse();
        assertThatThrownBy(() -> new FileSystemWorkspaceImportStorage(root, key).write(batch, file, new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class);
    }
    @Test void 全新进程恢复密文不依赖静态缓存() throws Exception {
        byte[] bytes = {'a'}; var batch = batch(bytes); var file = batch.files().getFirst();
        new FileSystemWorkspaceImportStorage(root, key).write(batch, file, new ByteArrayInputStream(bytes));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        var env = builder.environment(); env.put("CM_IMPORT_TEST_ROOT", root.toString()); env.put("CM_IMPORT_TEST_KEY", key);
        env.put("CM_IMPORT_TEST_TENANT", batch.scope().tenantId().toString()); env.put("CM_IMPORT_TEST_AGENT", batch.scope().agentId().toString());
        env.put("CM_IMPORT_TEST_ID", batch.id().toString()); env.put("CM_IMPORT_TEST_FILE", file.id().toString()); env.put("CM_IMPORT_TEST_TIME", batch.createdAt().toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try { assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test @EnabledOnOs(OS.LINUX)
    void 私有卷路径别名和对象链接不能绕过隔离(@TempDir Path other) throws Exception {
        // Windows 创建符号链接通常要求额外系统权限，不能以未创建链接冒充该分支通过；在Rocky实测。
        var alias = root.resolve("alias"); Files.createSymbolicLink(alias, other);
        assertThatThrownBy(() -> new FileSystemWorkspaceImportStorage(alias, key)).isInstanceOf(IllegalStateException.class);
        byte[] bytes = {'a'}; var batch = batch(bytes); var file = batch.files().getFirst();
        var storage = new FileSystemWorkspaceImportStorage(root, key); storage.write(batch, file, new ByteArrayInputStream(bytes));
        var object = root.resolve(batch.scope().tenantId().toString()).resolve(batch.id().toString()).resolve(file.id() + ".gcm");
        var displaced = other.resolve("unrelated"); Files.move(object, displaced); Files.createSymbolicLink(object, displaced);
        assertThatThrownBy(() -> storage.readVerified(batch, file)).isInstanceOf(IOException.class);
        storage.delete(batch); assertThat(Files.exists(displaced)).isTrue();
    }
    /** 仅测试子进程入口，失败只返回退出码，环境密钥不写命令行或输出。 */
    public static final class Probe {
        /** @param args 无参数；合成归属仅通过受控测试环境读取 */
        public static void main(String[] args) {
            try {
                var scope = new WorkspaceImportScope(UUID.fromString(System.getenv("CM_IMPORT_TEST_TENANT")), "owner", UUID.fromString(System.getenv("CM_IMPORT_TEST_AGENT")));
                var file = new WorkspaceImportFile(UUID.fromString(System.getenv("CM_IMPORT_TEST_FILE")), "资料.txt", 1, WorkspaceContextResource.digest("a"));
                var now = Instant.parse(System.getenv("CM_IMPORT_TEST_TIME"));
                var batch = WorkspaceImport.draft(UUID.fromString(System.getenv("CM_IMPORT_TEST_ID")), scope, "合成目录", List.of(file), now, now.plusSeconds(60));
                if (!Arrays.equals(new byte[]{'a'}, new FileSystemWorkspaceImportStorage(Path.of(System.getenv("CM_IMPORT_TEST_ROOT")), System.getenv("CM_IMPORT_TEST_KEY")).readVerified(batch, file)))
                    throw new IllegalStateException();
            } catch (Exception failure) { System.exit(1); }
        }
    }
}
