package com.cmagent.server.runtime;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Path;
import java.util.HexFormat;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

/**
 * 通过只读目录句柄取得Windows卷标识和128位文件ID，不用路径字符串代替目录身份。
 *
 * <p>本机JDK21实测BasicFileAttributes.fileKey()为空；必须使用FileIdInfo，而非降低到仅比较路径。
 * FileAttributeTagInfo同时拒绝重解析根；共享读写/删除避免读取元数据时锁住正常项目操作。
 * 这些检查不替代文件访问层对祖先目录、网络卷和每次访问的完整安全策略。</p>
 */
final class WindowsLocalProjectDirectoryIdentity {
    private WindowsLocalProjectDirectoryIdentity() {}

    /**
     * @param directory 受信原生选择结果的绝对路径
     * @return 卷序列号及128位文件ID的十六进制投影，不包含宿主路径
     * @throws LocalProjectNativeException 无法读取身份、不是普通目录或释放失败
     */
    static String read(Path directory) throws LocalProjectNativeException {
        Pointer handle = null;
        try (var attributes = new Memory(8); var identity = new Memory(24)) {
            // FILE_FLAG_BACKUP_SEMANTICS允许打开目录；OPEN_REPARSE_POINT禁止跟随根重解析目标。
            handle = Api.KERNEL.CreateFileW(new WString(directory.toString()), 0x80, 7, null, 3, 0x02200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1) {
                handle = null;
                throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            }
            if (!Api.KERNEL.GetFileInformationByHandleEx(handle, 9, attributes, 8)
                    || (attributes.getInt(0) & 0x10) == 0 || (attributes.getInt(0) & 0x400) != 0
                    || !Api.KERNEL.GetFileInformationByHandleEx(handle, 18, identity, 24))
                throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            byte[] value = identity.getByteArray(0, 24);
            boolean identified = false;
            for (int i = 8; i < value.length; i++) if (value[i] != 0) identified = true;
            if (!identified) throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            return HexFormat.of().formatHex(value);
        } catch (LocalProjectNativeException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
        } finally {
            if (handle != null) {
                try {
                    if (!Api.KERNEL.CloseHandle(handle)) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
                } catch (RuntimeException | LinkageError failure) {
                    throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
                }
            }
        }
    }

    /** 仅Windows调用方触发加载，不影响POSIX票据测试或旧服务启动。 */
    private static final class Api {
        /** 不提供写入或修改权限的系统映射。 */
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    /** Win32只读元数据和精确句柄释放。 */
    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer file, int type, Pointer result, int size);
        boolean CloseHandle(Pointer handle);
    }
}

