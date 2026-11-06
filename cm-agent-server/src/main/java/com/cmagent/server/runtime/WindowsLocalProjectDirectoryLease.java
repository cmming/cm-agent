package com.cmagent.server.runtime;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

/**
 * Windows写入期间的根/父目录句柄守卫，只管理本次路径链，不授予文件修改权限。
 *
 * <p>必须请求FILE_LIST_DIRECTORY而非仅READ_ATTRIBUTES：Win32属性访问不参与普通共享约束。
 * 默认目录只共享读；重命名专用分支允许Windows内部父目录写访问，但仍不共享删除。
 * 两分支均在操作前后复验真实目录属性和最终路径，拒绝重解析点及父对象改名。
 * 仍不承诺抵御同OS账号的任意代码/管理权限，不是硬隔离。</p>
 */
final class WindowsLocalProjectDirectoryLease implements AutoCloseable {
    /** 每个句柄与其已核验的规范路径，一起释放，不能复用于其他项目。 */
    private final List<Held> handles = new ArrayList<>();
    /** 关闭幂等，避免已回收句柄被再次使用。 */
    private boolean closed;

    /**
     * @param root 受信已登记根
     * @param parent 已通过相对路径策略定位的根内父目录
     * @param deadline 协作式执行期限
     * @return 本次目录链句柄的唯一所有者
     */
    static WindowsLocalProjectDirectoryLease acquire(LocalProjectDirectoryTickets.Root root, Path parent, long deadline)
            throws LocalProjectFileException {
        return acquire(root, parent, deadline, 1);
    }

    /**
     * Windows同句柄重命名内部需要父目录写访问；不共享写会返回ERROR_SHARING_VIOLATION。
     * 此分支保留不共享DELETE以保护父对象，并复核重解析属性；不承诺同OS账号的任意并发代码隔离。
     */
    static WindowsLocalProjectDirectoryLease acquireForRename(LocalProjectDirectoryTickets.Root root, Path parent, long deadline)
            throws LocalProjectFileException {
        return acquire(root, parent, deadline, 3);
    }

    private static WindowsLocalProjectDirectoryLease acquire(LocalProjectDirectoryTickets.Root root, Path parent, long deadline, int share)
            throws LocalProjectFileException {
        var lease = new WindowsLocalProjectDirectoryLease();
        boolean success = false;
        try {
            if (!Platform.isWindows() || Native.POINTER_SIZE != 8) throw new LocalProjectFileException(UNAVAILABLE);
            parent = parent.toAbsolutePath().normalize();
            if (!parent.toAbsolutePath().normalize().startsWith(root.directory())) throw new LocalProjectFileException(INVALID_PATH);
            Path current = root.directory();
            lease.open(current, root.identity(), deadline, share);
            Path relative = root.directory().relativize(parent);
            if (!relative.toString().isEmpty()) {
                for (Path part : relative) {
                    current = current.resolve(part);
                    lease.open(current, null, deadline, share);
                }
            }
            success = true;
            return lease;
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectFileException(UNAVAILABLE); }
        finally { if (!success) lease.close(); }
    }

    private void open(Path path, String rootIdentity, long deadline, int share) throws LocalProjectFileException {
        WindowsLocalProjectTextReader.budget(deadline);
        Pointer handle = Api.KERNEL.CreateFileW(new WString(path.toString()), 0x81, share, null, 3, 0x02200000, null);
        if (handle == null || Pointer.nativeValue(handle) == -1) {
            int error = Native.getLastError();
            throw new LocalProjectFileException(error == 32 ? FILE_BUSY : error == 5 ? ACCESS_DENIED : UNAVAILABLE);
        }
        handles.add(new Held(handle, path));
        try (var attributes = new Memory(8); var identity = new Memory(24)) {
            if (!Api.KERNEL.GetFileInformationByHandleEx(handle, 9, attributes, 8)
                    || !Api.KERNEL.GetFileInformationByHandleEx(handle, 18, identity, 24))
                throw new LocalProjectFileException(UNAVAILABLE);
            int flags = attributes.getInt(0);
            if ((flags & 0x10) == 0 || (flags & 0x400) != 0) throw new LocalProjectFileException(UNSAFE_FILE);
            if (rootIdentity != null && !rootIdentity.equals(HexFormat.of().formatHex(identity.getByteArray(0, 24))))
                throw new LocalProjectFileException(ROOT_CHANGED);
            verifyPath(handle, path);
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectFileException(UNAVAILABLE); }
    }

    /** 在真正修改前复核持有对象的路径，不把句柄创建成功等同于全部前置条件仍有效。 */
    void verify() throws LocalProjectFileException {
        if (closed) throw new LocalProjectFileException(UNAVAILABLE);
        for (Held held : handles) verifyPath(held.handle(), held.path());
    }

    private static void verifyPath(Pointer handle, Path path) throws LocalProjectFileException {
        try (var attributes = new Memory(8)) {
            if (!Api.KERNEL.GetFileInformationByHandleEx(handle, 9, attributes, 8)) throw new LocalProjectFileException(UNAVAILABLE);
            int flags = attributes.getInt(0);
            if ((flags & 0x10) == 0 || (flags & 0x400) != 0) throw new LocalProjectFileException(UNSAFE_FILE);
        }
        char[] name = new char[32768];
        int length = Api.KERNEL.GetFinalPathNameByHandleW(handle, name, name.length, 0);
        if (length < 1 || length >= name.length) throw new LocalProjectFileException(UNAVAILABLE);
        String value = new String(name, 0, length);
        if (value.startsWith("\\\\?\\")) value = value.substring(4);
        if (!Path.of(value).toAbsolutePath().normalize().equals(path)) throw new LocalProjectFileException(FILE_CHANGED);
    }

    @Override public void close() throws LocalProjectFileException {
        if (closed) return;
        closed = true;
        boolean failed = false;
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { if (!Api.KERNEL.CloseHandle(handles.get(i).handle())) failed = true; }
            catch (RuntimeException | LinkageError failure) { failed = true; }
        }
        handles.clear();
        if (failed) throw new LocalProjectFileException(UNAVAILABLE);
    }

    /**
     * @param handle 本次打开的目录句柄
     * @param path 已核验规范路径，不输出日志或交付外部
     */
    private record Held(Pointer handle, Path path) {}

    private static final class Api {
        /** 延迟加载，非Windows调用方不得调用acquire。 */
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }
    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer file, int type, Pointer result, int size);
        int GetFinalPathNameByHandleW(Pointer file, char[] result, int count, int flags);
        boolean CloseHandle(Pointer handle);
    }
}

