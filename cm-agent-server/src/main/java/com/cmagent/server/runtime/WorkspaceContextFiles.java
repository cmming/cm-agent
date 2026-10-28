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
    /** 仅Windows登记资料使用；原生DLL在首次读取安全描述符时才加载。 */
    private final WindowsWorkspaceAclPolicy windows;

    WorkspaceContextFiles() { this(List.of(applicationRoot(), Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())); }
    /** 测试可指定合成保护目录；生产固定使用真实应用与用户目录。 */
    WorkspaceContextFiles(List<Path> protectedRoots) {
        this(protectedRoots, new WindowsWorkspaceAclPolicy(new WindowsSecurityDescriptorReader()));
    }
    /** 注入只读描述符适配用于故障/资源边界测试，不改变生产权限策略。 */
    WorkspaceContextFiles(List<Path> protectedRoots, WindowsWorkspaceAclPolicy windows) {
        this.protectedRoots = List.copyOf(protectedRoots); this.windows = windows;
    }

    List<WorkspaceContextResource> read(WorkspaceContextProperties policy, WorkspaceContextProperties.Binding binding) throws IOException {
        long deadline = System.nanoTime() + policy.getReadTimeout().toNanos();
        Path root = checkedRoot(policy);
        var rootIdentity = attributes(root);
        var access = access(root, deadline);
        if (!same(rootIdentity, attributes(root))) throw new Invalid("资料根无法稳定校验身份");
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
            if (access != null) {
                Path selected = root;
                for (String component : components) { selected = selected.resolve(component); access.verify(selected); }
            }
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
        if (access != null) access.finish();
        return List.copyOf(result);
    }

    /**
     * Windows审批恢复/ASK前只复核仍存在的原路径ACL，绝不重新读正文或替换原快照。
     * 源文件删除仍允许使用原快照，但根必须存在，且仍存活的选中祖先必须可信。
     * POSIX保留原恢复语义，不因Windows兼容调整增加文件存在性要求。
     */
    void verifyAccess(WorkspaceContextProperties policy, WorkspaceContextProperties.Binding binding) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) return;
        long deadline = System.nanoTime() + policy.getReadTimeout().toNanos();
        Path root = checkedRoot(policy);var access = access(root, deadline);
        if (access == null) throw new IOException("Windows资料权限视图不可用");
        for (String relative : binding.files()) {
            var components = new ArrayList<String>(List.of(binding.directory().split("/")));
            components.addAll(List.of(relative.split("/")));Path selected = root;
            for (int i = 0; i < components.size(); i++) {
                selected = selected.resolve(components.get(i));
                try {
                    var value = attributes(selected);
                    if (value.isSymbolicLink() || !selected.toRealPath().equals(selected.toAbsolutePath().normalize())) throw new Invalid("资料路径不允许重解析或别名");
                    if (i < components.size() - 1 && !value.isDirectory() || i == components.size() - 1 && !value.isRegularFile())
                        throw new Invalid("原资料路径类型已改变");
                    access.verify(selected);
                } catch (NoSuchFileException missing) {
                    // 缺失对象没有可读取ACL；保留R0删除源文件后使用原快照的合同，不读取任何替代文件。
                    break;
                }
            }
        }
        access.finish();
    }
    private Path checkedRoot(WorkspaceContextProperties policy) throws IOException {
        Path root = Path.of(policy.getRootDirectory()).toAbsolutePath().normalize();
        if (root.toString().startsWith("\\\\") || protectedRoots.stream().anyMatch(path -> root.startsWith(path) || path.startsWith(root)))
            throw new Invalid("资料根属于禁止来源");
        checkDirectories(root);
        String type = Files.getFileStore(root).type().toLowerCase(Locale.ROOT);
        if (Set.of("nfs", "nfs4", "cifs", "smbfs", "fuse.sshfs").contains(type)) throw new Invalid("资料根不支持网络文件系统");
        return root;
    }
    private WindowsWorkspaceAclPolicy.Access access(Path root, long deadline) throws IOException {
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) { requirePrivate(root); return null; }
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) throw new Invalid("资料根权限视图不受支持");
        return windows.open(root, deadline);
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
        // Windows无SecureDirectoryStream：受信ACL限制写入者，前后目录身份与ACL复核检测部署者更新竞态。
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
    /** POSIX仍接受所有者私有根，不因Windows可信系统身份兼容而放宽。 */
    private static void requirePrivate(Path root) throws IOException {
        var posix = Files.getFileAttributeView(root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (posix.readAttributes().permissions().stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
                throw new Invalid("资料根必须为所有者私有目录");
        } else throw new Invalid("资料根POSIX权限视图不可用");
    }
    private static Path applicationRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path path = start; path != null; path = path.getParent()) if (Files.exists(path.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return path;
        return start;
    }
    /** 文件不满足声明的只读格式或路径边界，不包含宿主路径。 */ static final class Invalid extends IOException { Invalid(String message) { super(message); } }
    /** 文件数量或实际字节超限。 */ static final class Limit extends IOException { Limit() { super("资料字节超限"); } }
}
