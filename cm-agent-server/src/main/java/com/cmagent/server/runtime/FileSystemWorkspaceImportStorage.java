package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.WorkspaceImportStorage;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/**
 * 独立 AES-256/GCM 私有卷。对象键仅由可信 tenant/import/file UUID 构成，AAD 绑定原主体、Agent 和 manifest。
 * 固定暂存文件、原子发布、文件锁和持久删除标记防止崩溃半写、覆盖及 GC 后迟到写入。
 */
public final class FileSystemWorkspaceImportStorage implements WorkspaceImportStorage {
    @Override public synchronized boolean contains(WorkspaceImport batch, WorkspaceImportFile file) throws IOException {
        member(batch, file);
        try {
            Path path = directory(batch).resolve(file.id() + ".gcm");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            return true;
        } catch (Exception unavailable) { throw new IOException("导入对象状态暂不可核对"); }
    }
    /** 部署指定的独立私有本地持久卷，不接受浏览器路径。 */
    private final Path root;
    /** 仅当前实例持有的独立主密钥，禁止输出、默认值或复用其他用途密钥。 */
    private final SecretKeySpec key;
    /** 每次加密生成全新 96 位 IV。 */
    private final SecureRandom random = new SecureRandom();
    /** 文件格式版本与 Skill 产物格式分开。 */
    private static final byte[] MAGIC = {'C', 'M', 'I', '1'};

    /**
     * 初始化仅在部署显式启用后发生；本类不持有长连接，文件句柄按调用关闭。
     * @param directory 独立私有持久卷根，禁止应用目录、主目录自身或其祖先、静态目录及网络卷
     * @param encryptionKey 独立 Base64 AES-256 密钥
     */
    public FileSystemWorkspaceImportStorage(Path directory, String encryptionKey) {
        try {
            root = directory.toAbsolutePath().normalize();
            byte[] bytes = Base64.getDecoder().decode(encryptionKey);
            try { if (bytes.length != 32) throw new IllegalArgumentException(); key = new SecretKeySpec(bytes, "AES"); }
            finally { Arrays.fill(bytes, (byte) 0); }
            Path working = Path.of("").toAbsolutePath().normalize(), home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            if (root.getParent() == null || root.startsWith(working) || working.startsWith(root) || home.startsWith(root)
                    || root.toString().replace('\\', '/').matches("(?i).*/(target|static|resources|\\.git)(/.*)?")
                    || root.toString().startsWith("\\\\")) throw new IOException();
            privateDirectory(root);
            if (!root.toRealPath().equals(root) || Set.of("nfs", "nfs4", "cifs", "smbfs").contains(Files.getFileStore(root).type().toLowerCase(Locale.ROOT)))
                throw new IOException();
        } catch (Exception invalid) { throw new IllegalStateException("导入存储需要独立私有本地持久卷及有效加密密钥"); }
    }

    private static void privateDirectory(Path directory) throws IOException {
        for (Path p = directory; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch (UnsupportedOperationException windows) { Files.createDirectories(directory); }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        else {
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl == null) throw new IOException();
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(directory))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
    }

    private Path directory(WorkspaceImport batch) throws IOException {
        Path tenant = root.resolve(batch.scope().tenantId().toString()); privateDirectory(tenant);
        Path directory = tenant.resolve(batch.id().toString()); privateDirectory(directory); return directory;
    }

    private static void member(WorkspaceImport batch, WorkspaceImportFile file) throws IOException {
        if (!batch.files().contains(file)) throw new IOException("导入对象不属于当前清单");
    }

    private Cipher cipher(int mode, WorkspaceImport batch, WorkspaceImportFile file, byte[] iv) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode, key, new GCMParameterSpec(128, iv));
        // manifest 摘要已绑定可信 scope、所有文件与固定顺序；额外绑定 fileId 防止对象互换。
        cipher.updateAAD(("IMPORTED:" + batch.manifestDigest() + ":" + file.id()).getBytes(StandardCharsets.UTF_8)); return cipher;
    }

    private static void privateFile(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException windows) { /* Windows 文件继承上层独占 ACL。 */ }
    }

    private static FileChannel newFile(Path file) throws IOException {
        var options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) != null)
            return FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return FileChannel.open(file, options);
    }

    @Override public void write(WorkspaceImport batch, WorkspaceImportFile file, InputStream input) throws IOException {
        member(batch, file); byte[] plain = null;
        try {
            // 不使用 readAllBytes；声明长度+1 能区分超限，拒绝而非截断。输入流由 HTTP/调用者关闭。
            plain = input.readNBytes(file.sizeBytes() + 1); validate(file, plain);
            // 慢输入不占实例发布锁；底层 HTTP 超时/并发上限由上传边界承担，验证后才串行化短磁盘操作。
            synchronized (this) {
            Path dir = directory(batch), target = dir.resolve(file.id() + ".gcm"), stage = dir.resolve(file.id() + ".stage");
            try (var lockChannel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = lockChannel.lock()) {
                privateFile(dir.resolve(".lock"));
                if (Files.exists(dir.resolve(".deleted"), LinkOption.NOFOLLOW_LINKS) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                byte[] iv = new byte[12]; random.nextBytes(iv);
                byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, batch, file, iv).doFinal(plain);
                // 固定 stage 若已存在说明上次结果未知；禁止静默覆盖，由补偿/撤销回收处理。
                try (var channel = newFile(stage)) {
                    var bytes = ByteBuffer.allocate(16 + encrypted.length).put(MAGIC).put(iv).put(encrypted).flip();
                    while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
                }
                try {
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                    forceDirectory(dir);
                } finally { Files.deleteIfExists(stage); }
            }
            }
        } catch (ContentFailure controlled) { throw controlled; }
        catch (Exception unavailable) { throw new IOException("导入文件未能安全持久化，请核对批次状态"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    @Override public synchronized byte[] readVerified(WorkspaceImport batch, WorkspaceImportFile file) throws IOException {
        member(batch, file); byte[] plain = null;
        try {
            Path dir = directory(batch), path = dir.resolve(file.id() + ".gcm");
            try (var lockChannel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = lockChannel.lock()) {
                privateFile(dir.resolve(".lock"));
                if (Files.exists(dir.resolve(".deleted"), LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) != file.sizeBytes() + 32L) throw new IOException();
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] all = input.readNBytes(file.sizeBytes() + 33);
                    if (all.length != file.sizeBytes() + 32 || !Arrays.equals(MAGIC, Arrays.copyOf(all, 4))) throw new IOException();
                    plain = cipher(Cipher.DECRYPT_MODE, batch, file, Arrays.copyOfRange(all, 4, 16)).doFinal(all, 16, all.length - 16);
                    validate(file, plain); byte[] result = plain; plain = null; return result;
                }
            }
        } catch (Exception invalid) { throw new IOException("导入密文缺失或完整性验证失败"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private static void validate(WorkspaceImportFile file, byte[] bytes) throws ContentFailure, NoSuchAlgorithmException {
        if (bytes.length != file.sizeBytes() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256()))
            throw new ContentFailure("文件实际长度或摘要与确认清单不一致，请重新选择");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                throw new ContentFailure("仅支持非空的 UTF-8 文本资料");
        } catch (CharacterCodingException invalid) { throw new ContentFailure("仅支持有效的 UTF-8 文本资料"); }
    }

    @Override public synchronized void delete(WorkspaceImport batch) throws IOException {
        try {
            Path dir = directory(batch);
            try (var lockChannel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = lockChannel.lock()) {
                privateFile(dir.resolve(".lock"));
                Path marker = dir.resolve(".deleted");
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) try (var channel = newFile(marker)) { channel.force(true); }
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                forceDirectory(dir);
                for (var file : batch.files()) {
                    Files.deleteIfExists(dir.resolve(file.id() + ".gcm")); Files.deleteIfExists(dir.resolve(file.id() + ".stage"));
                }
                forceDirectory(dir);
            }
        } catch (Exception unavailable) { throw new IOException("导入密文回收未完成，将保留配额并等待重试"); }
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Linux 在发布和删除后刷目录元数据；Windows 不支持目录 FileChannel，依赖私有 NTFS 持久卷。
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    /** 不可信内容拒绝与基础设施失败分开，消息不含名称、路径或正文，供未来 HTTP 边界映射422。 */
    public static final class ContentFailure extends IOException {
        /** @param message 仅允许调用本类固定脱敏说明 */
        private ContentFailure(String message) { super(message); }
    }
}
