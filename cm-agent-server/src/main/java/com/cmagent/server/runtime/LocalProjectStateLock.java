package com.cmagent.server.runtime;

import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Set;
import static com.cmagent.server.runtime.WindowsWorkspaceAclPolicy.*;

/** 私有状态卷的实例独占；锁丢失或目录身份变化时不能继续派发本机副作用。 */
final class LocalProjectStateLock implements AutoCloseable {
    /** 固定规范目录，不从请求取值。 */ private final Path directory;
    /** 启动时原生身份，阻止同路径换卷/换目录。 */ private final String identity;
    /** 独占文件句柄由本对象创建并持有至服务关闭。 */ private final FileChannel channel;
    /** 与同一卷上其他CM Agent实例互斥。 */ private final FileLock lock;

    LocalProjectStateLock(Path configured) {
        FileChannel opened = null; FileLock acquired = null;
        try {
            directory = configured.toAbsolutePath().normalize();
            Path working = Path.of("").toAbsolutePath().normalize(), home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            if (!Platform.isWindows() || directory.getParent() == null || directory.startsWith(working)
                    || working.startsWith(directory) || home.startsWith(directory) || directory.toString().startsWith("\\\\")) throw new IOException();
            for (Path cursor = directory; cursor != null; cursor = cursor.getParent())
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            if (!directory.equals(directory.toRealPath())) throw new IOException();
            WindowsLocalProjectTextReader.requireLocalDrive(directory);
            verifyPrivate(directory);
            identity = WindowsLocalProjectDirectoryIdentity.read(directory);
            Path file = directory.resolve(".instance-lock");
            if (Files.isSymbolicLink(file)) throw new IOException();
            opened = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            acquired = opened.tryLock();
            if (acquired == null) throw new IOException();
            verifyPrivate(file);
            if (!identity.equals(WindowsLocalProjectDirectoryIdentity.read(directory))) throw new IOException();
            channel = opened; lock = acquired;
        } catch (Exception failure) {
            if (acquired != null) try { acquired.release(); } catch (IOException ignored) { /* 保留初始化拒绝，不回退为无锁运行。 */ }
            if (opened != null) try { opened.close(); } catch (IOException ignored) { /* 未成功装配的句柄只执行一次补偿关闭。 */ }
            throw new IllegalStateException("本地项目需要预建的私有本机状态目录，且只能由一个服务实例持有");
        }
    }

    /** 仅复用只读原生描述符，不改变旧参考目录ACL策略，也不自动收紧用户已有目录。 */
    private static void verifyPrivate(Path path) throws IOException {
        var descriptor = new WindowsSecurityDescriptorReader().read(path, System.nanoTime() + 5_000_000_000L);
        Set<String> trusted = new java.util.HashSet<>(java.util.List.of(descriptor.ownerSid(), "S-1-5-18", "S-1-5-32-544"));
        if (descriptor.dacl() != Dacl.PRESENT || descriptor.entries().isEmpty()) throw new IOException();
        for (var entry : descriptor.entries())
            if (entry.type() != 0 && entry.type() != 1 || entry.type() == 0 && !trusted.contains(entry.sid())) throw new IOException();
    }

    /** 每次副作用派发必须再次核验，不能仅依赖启动时的独占成功。 */
    void requireHeld() {
        try {
            if (!lock.isValid() || !channel.isOpen() || !identity.equals(WindowsLocalProjectDirectoryIdentity.read(directory))) throw new IOException();
            verifyPrivate(directory);
        } catch (Exception failure) { throw new IllegalStateException("本地项目状态卷或实例锁已不可用"); }
    }
    Path directory() { return directory; }

    /** 仅为本服务建立固定单层状态子目录，不接受外部路径且不修改用户项目权限。 */
    Path privateSubdirectory(String name) {
        requireHeld();
        if(!Set.of("temp","changes").contains(name))throw new IllegalArgumentException("私有状态子目录名称无效");
        Path selected=directory.resolve(name);
        try {
            try { Files.createDirectory(selected); } catch(FileAlreadyExistsException exists) { /* 已有恢复目录必须复核，不能覆盖。 */ }
            if(!Files.isDirectory(selected,LinkOption.NOFOLLOW_LINKS)||!selected.equals(selected.toRealPath()))throw new IOException();
            verifyPrivate(selected);return selected;
        } catch(IOException failure) { throw new IllegalStateException("本地项目私有子目录不可用"); }
    }

    /** Spring在停止新请求后释放；关闭失败不能作为成功静默吞掉。 */
    @Override public void close() throws IOException { try { if (lock.isValid()) lock.release(); } finally { channel.close(); } }
}
