package com.cmagent.server.runtime;

import com.cmagent.core.domain.WorkspaceContextResource;
import com.cmagent.server.config.WorkspaceContextProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * 有界只读文件入口；只打开管理员私有目录和明确声明的文本，不产生临时副本。
 * Linux使用安全目录句柄，Windows通过私有ACL及逐层身份复核保护可信管理员目录。
 * 同一服务账号及部署管理员是文件系统可信写入者，不能向模型提供这些账号的写入通道。
 */
final class WorkspaceContextFiles {
    /** 应用与用户目录属于禁止来源，构造后固定，不接受客户端覆盖。 */
    private final List<Path> protectedRoots;

    WorkspaceContextFiles() { this(List.of(applicationRoot(), Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())); }
    /** 测试可指定合成保护目录；生产固定使用真实应用与用户目录。 */
    WorkspaceContextFiles(List<Path> protectedRoots) { this.protectedRoots = List.copyOf(protectedRoots); }

    List<WorkspaceContextResource> read(WorkspaceContextProperties policy, WorkspaceContextProperties.Binding binding) throws IOException {
        Path root = Path.of(policy.getRootDirectory()).toAbsolutePath().normalize();
        if (root.toString().startsWith("\\\\") || protectedRoots.stream().anyMatch(path -> root.startsWith(path) || path.startsWith(root)))
            throw new Invalid("资料根属于禁止来源");
        checkDirectories(root);
        var rootIdentity = attributes(root);
        requirePrivate(root);
        if (!same(rootIdentity, attributes(root))) throw new Invalid("资料根无法稳定校验身份");
        String type = Files.getFileStore(root).type().toLowerCase(Locale.ROOT);
        if (Set.of("nfs", "nfs4", "cifs", "smbfs", "fuse.sshfs").contains(type)) throw new Invalid("资料根不支持网络文件系统");
        long deadline = System.nanoTime() + policy.getReadTimeout().toNanos();
        List<WorkspaceContextResource> result = new ArrayList<>();
        int total = 0;
        for (String relative : binding.files()) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw new IOException("资料读取预算耗尽或已中断");
            var components = new ArrayList<String>(List.of(binding.directory().split("/")));
            components.addAll(List.of(relative.split("/")));
            Path file = root;
            for (int i = 0; i < components.size() - 1; i++) file = file.resolve(components.get(i));
            checkDirectories(file);
            file = file.resolve(components.getLast());
            var before = attributes(file);
            if (!before.isRegularFile() || before.isSymbolicLink()) throw new Invalid("资料不是普通文本文件");
            if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) throw new Invalid("资料路径不允许重解析或别名");
            try {
                if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                    throw new Invalid("资料不允许硬链接");
            } catch (UnsupportedOperationException ignored) { /* Windows私有ACL阻止不可信主体向资料根植入链接。 */ }
            if (before.size() < 1) throw new Invalid("资料文件不能为空");
            if (before.size() > policy.getMaxFileBytes() || before.size() > policy.getMaxTotalBytes() - total)
                throw new Limit();
            byte[] bytes;
            // 安全目录句柄保持逐层归属，不能在检查后通过替换中间目录的链接读取根之外的文件。
            try (var stream = Files.newDirectoryStream(root)) {
                if (stream instanceof SecureDirectoryStream<Path> secure) {
                    if (!Objects.equals(rootIdentity.fileKey(), secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))
                        throw new IOException("资料根身份发生变化");
                    bytes = readSecure(secure, components, 0, policy, deadline);
                }
                else bytes = readChecked(file, policy, deadline);
            }
            var after = attributes(file);
            if (!same(before, after)) throw new IOException("资料在冻结期间发生变化");
            total = Math.addExact(total, bytes.length);
            if (total > policy.getMaxTotalBytes()) throw new Limit();
            String content;
            try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException failure) { throw new Invalid("资料必须采用合法UTF-8文本"); }
            if (content.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t'))
                throw new Invalid("资料包含二进制控制字符");
            result.add(new WorkspaceContextResource(relative, content, bytes.length, WorkspaceContextResource.digest(content)));
        }
        return List.copyOf(result);
    }

    private byte[] readSecure(SecureDirectoryStream<Path> directory, List<String> components, int index,
            WorkspaceContextProperties policy, long deadline) throws IOException {
        Path relative = Path.of(components.get(index));
        if (index < components.size() - 1) {
            try (var next = directory.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS)) {
                return readSecure(next, components, index + 1, policy, deadline);
            }
        }
        try (var channel = directory.newByteChannel(relative, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return bounded(channel, policy.getMaxFileBytes(), deadline);
        }
    }

    private byte[] readChecked(Path file, WorkspaceContextProperties policy, long deadline) throws IOException {
        // Windows无SecureDirectoryStream：私有根ACL是写入信任边界，前后目录身份核对拒绝普通管理员更新竞态。
        var parents = new LinkedHashMap<Path, BasicFileAttributes>();
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) parents.put(parent, attributes(parent));
        byte[] bytes;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = bounded(channel, policy.getMaxFileBytes(), deadline);
        }
        for (var parent : parents.entrySet()) if (!same(parent.getValue(), attributes(parent.getKey()))) throw new IOException("资料目录身份发生变化");
        return bytes;
    }

    private static byte[] bounded(SeekableByteChannel channel, int limit, long deadline) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(Math.min(4096, limit + 1));
        while (channel.read(buffer) != -1) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw new IOException("资料读取预算耗尽或已中断");
            buffer.flip();
            if (buffer.remaining() > limit - output.size()) throw new Limit();
            output.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
        }
        return output.toByteArray();
    }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
    private static boolean same(BasicFileAttributes before, BasicFileAttributes after) {
        // 当前Windows/JDK21实测fileKey可能为null；私有ACL下以创建时间补充可信管理员更新检测。
        // Linux安全目录句柄另核对已打开根的身份，不依赖路径复核提供原子目录保护。
        return !after.isSymbolicLink() && Objects.equals(before.fileKey(), after.fileKey())
                && before.creationTime().equals(after.creationTime()) && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime());
    }
    private static void checkDirectories(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component); var attributes = attributes(current);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new Invalid("资料目录不允许符号链接或非目录");
            // Windows目录联接不一定被BasicFileAttributes标为symlink；最终路径变化同样拒绝。
            if (!current.toRealPath().equals(current.toAbsolutePath().normalize())) throw new Invalid("资料目录不允许重解析或路径别名");
        }
    }
    /** 不改变已有资料权限；仅接受所有者私有根，部署者须先配置ACL/权限。 */
    private static void requirePrivate(Path root) throws IOException {
        var posix = Files.getFileAttributeView(root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (posix.readAttributes().permissions().stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
                throw new Invalid("资料根必须为所有者私有目录");
        } else {
            var acl = Files.getFileAttributeView(root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null || acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(aclOwner(root))))
                throw new Invalid("资料根必须采用所有者私有ACL");
        }
    }
    private static UserPrincipal aclOwner(Path root) { try { return Files.getOwner(root, LinkOption.NOFOLLOW_LINKS); } catch (IOException failure) { throw new IllegalStateException("资料根归属不可用"); } }
    private static Path applicationRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path path = start; path != null; path = path.getParent()) if (Files.exists(path.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return path;
        return start;
    }
    /** 文件不满足声明的只读格式或路径边界，不包含宿主路径。 */ static final class Invalid extends IOException { Invalid(String message) { super(message); } }
    /** 文件数量或实际字节超限。 */ static final class Limit extends IOException { Limit() { super("资料字节超限"); } }
}
