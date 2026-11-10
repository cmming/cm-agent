package com.cmagent.localclient;

import com.cmagent.core.runtime.LocalProjectPathPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 当前操作系统用户范围的项目根登记，确保不同 state-dir、设备重配或 Server 不能绕过根冲突。
 *
 * <p>登记文件只位于当前用户主目录下的私有目录。每次新增都在同一文件锁内重新读取并检查
 * 规范路径、目录对象身份及祖先/后代关系；冲突时拒绝而非猜测。绝对路径不会进入返回值
 * 的字符串表示，也不交给 Server。</p>
 */
final class ClientProjectRootStore {
    private static final int VERSION = 1;
    private final Path directory;
    private final Path rootsFile;
    private final Path lockFile;
    private final ObjectMapper json;

    ClientProjectRootStore(Path directory, ObjectMapper json) {
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("项目根注册目录必须是绝对路径");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.rootsFile = this.directory.resolve("roots.json");
        this.lockFile = this.directory.resolve("roots.lock");
        this.json = Objects.requireNonNull(json);
    }

    static ClientProjectRootStore forCurrentUser(ObjectMapper json) {
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        return new ClientProjectRootStore(home.resolve(".cm-agent").resolve("client-project-roots"), json);
    }

    /**
     * 在跨进程排他锁中登记一个已选目录。
     *
     * @param identity 已完成配对的设备身份
     * @param registrationId 本次选择任务标识，用于结果丢失后的本机幂等
     * @param selected 用户确认的目录
     * @return 不向 Server 暴露绝对路径的根材料
     */
    RootBinding register(DeviceIdentityStore.StoredIdentity identity, UUID registrationId,
            Path selected) throws IOException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(registrationId);
        if (!Platform.isWindows()) {
            throw new IOException("首版只支持 Windows 本机项目目录");
        }
        Files.createDirectories(directory);
        Path canonical = validateDirectory(selected);
        String identityDigest = identity(canonical);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            Registry registry = readRegistry();
            for (RootBinding existing : registry.roots()) {
                if (existing.registrationId().equals(registrationId)) {
                    if (!existing.identity().equals(identityDigest)) {
                        throw new IOException("同一选择任务不能绑定不同目录");
                    }
                    return existing;
                }
                Path registered = Path.of(existing.path()).toAbsolutePath().normalize();
                if (existing.identity().equals(identityDigest) || overlaps(canonical, registered)) {
                    throw new RootConflictException();
                }
            }
            String displayName = canonical.getFileName() == null
                    ? "项目根" : canonical.getFileName().toString();
            if (displayName.isBlank()) displayName = "项目根";
            if (displayName.length() > 120) displayName = displayName.substring(0, 120);
            var metadata = identity.metadata();
            RootBinding binding = new RootBinding(UUID.randomUUID(), registrationId,
                    metadata.serverUrl(), metadata.tenantId(), metadata.ownerId(),
                    metadata.deviceId(), canonical.toString(), identityDigest, displayName,
                    Instant.now().toString());
            var next = new ArrayList<>(registry.roots());
            next.add(binding);
            writeRegistry(new Registry(VERSION, List.copyOf(next)));
            return binding;
        }
    }

    /** 根冲突是可预期的拒绝，不能向服务端或日志暴露本机路径。 */
    static final class RootConflictException extends IOException {
        RootConflictException() { super("该目录与已登记项目根相同或互相包含，请先使用原项目"); }
    }

    /**
     * 在每次文件操作前解析根并重核对象身份。
     */
    RootBinding verify(DeviceIdentityStore.StoredIdentity identity, UUID rootId) throws IOException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(rootId);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            RootBinding binding = readRegistry().roots().stream()
                    .filter(value -> value.rootId().equals(rootId))
                    .findFirst().orElseThrow(() -> new IOException("客户端项目根不存在"));
            var metadata = identity.metadata();
            if (!binding.serverUrl().equals(metadata.serverUrl())
                    || !binding.tenantId().equals(metadata.tenantId())
                    || !binding.ownerId().equals(metadata.ownerId())
                    || !binding.deviceId().equals(metadata.deviceId())) {
                throw new IOException("客户端项目根不属于当前设备身份");
            }
            Path current = validateDirectory(Path.of(binding.path()));
            if (!binding.identity().equals(identity(current))) {
                throw new IOException("客户端项目根目录对象已变化，请重新选择");
            }
            return binding;
        }
    }

    Path resolve(RootBinding binding, String relative) throws IOException {
        if (relative == null || relative.length() > 1024 || relative.indexOf('\0') >= 0
                || relative.startsWith("/") || relative.startsWith("\\")
                || relative.contains("\\") || relative.equals("..")
                || relative.startsWith("../") || relative.contains("/../")
                || relative.endsWith("/..")
                || !relative.isEmpty() && LocalProjectPathPolicy.excluded(relative)) {
            throw new IOException("项目内路径无效或受限");
        }
        Path root = validateDirectory(Path.of(binding.path()));
        Path resolved = relative.isEmpty() ? root : root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("项目路径越界");
        Path current = root;
        for (Path component : root.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException("项目路径包含符号链接");
            }
        }
        return resolved;
    }

    String identity(Path directory) throws IOException {
        Path canonical = directory.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(canonical,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("目录对象身份不可用");
        }
        return sha256("windows:" + WindowsClientProjectDirectoryIdentity.read(canonical));
    }

    private Path validateDirectory(Path selected) throws IOException {
        if (selected == null || !selected.isAbsolute()) {
            throw new IOException("必须选择绝对路径目录");
        }
        String raw = selected.toString();
        if (raw.startsWith("\\\\") || raw.startsWith("//")) {
            throw new IOException("首版不支持 UNC 或网络共享目录");
        }
        Path root = selected.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("选择项不是安全目录");
        }
        if (root.startsWith(directory) || directory.startsWith(root)) {
            throw new IOException("不能选择应用私有状态目录或其父目录");
        }
        String type = Files.getFileStore(root).type().toLowerCase(Locale.ROOT);
        if (type.contains("network") || type.contains("cifs") || type.contains("smb")
                || type.contains("nfs") || type.contains("fuse")) {
            throw new IOException("首版不支持网络项目根");
        }
        return root;
    }

    private boolean overlaps(Path first, Path second) throws IOException {
        Path left = first.toRealPath();
        Path right = second.toRealPath();
        return left.startsWith(right) || right.startsWith(left);
    }

    private Registry readRegistry() throws IOException {
        if (!Files.exists(rootsFile, LinkOption.NOFOLLOW_LINKS)) {
            return new Registry(VERSION, List.of());
        }
        Registry registry;
        try {
            registry = json.readValue(rootsFile.toFile(), Registry.class);
        } catch (RuntimeException | IOException invalid) {
            throw new IOException("客户端项目根登记损坏，需人工核对后恢复");
        }
        if (registry == null || registry.version() != VERSION || registry.roots() == null) {
            throw new IOException("客户端项目根登记版本无效");
        }
        return new Registry(registry.version(), List.copyOf(registry.roots()));
    }

    private void writeRegistry(Registry registry) throws IOException {
        Path temporary = directory.resolve("roots.json.tmp");
        try {
            json.writeValue(temporary.toFile(), registry);
            Files.move(temporary, rootsFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private record Registry(int version, List<RootBinding> roots) { }

    /**
     * 客户端私有根绑定，不进入 Server DTO。
     */
    record RootBinding(UUID rootId, UUID registrationId, String serverUrl, UUID tenantId,
            String ownerId, UUID deviceId, String path, String identity, String displayName,
            String createdAt) {
        RootBinding {
            Objects.requireNonNull(rootId);
            Objects.requireNonNull(registrationId);
            Objects.requireNonNull(serverUrl);
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(deviceId);
            Objects.requireNonNull(path);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(createdAt);
        }

        @Override public String toString() {
            return "RootBinding[rootId=" + rootId + ",identity=" + identity + "]";
        }
    }
}
