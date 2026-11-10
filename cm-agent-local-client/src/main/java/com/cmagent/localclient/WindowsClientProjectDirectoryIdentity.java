package com.cmagent.localclient;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;

/** 通过只读 Windows 对象句柄取得目录或文件的卷标识和 128 位对象 ID。 */
final class WindowsClientProjectDirectoryIdentity {
    private WindowsClientProjectDirectoryIdentity() { }

    /**
     * @param directory 已规范化的本地目录
     * @return 不含路径的目录对象身份
     * @throws IOException 无法取得身份、不是普通目录或句柄释放失败
     */
    static String read(Path directory) throws IOException {
        return readObject(directory, true);
    }

    /**
     * 读取普通文件的对象身份，用于在内容读取前后确认路径没有被同名对象替换。
     *
     * @param file 已规范化且不跟随重解析点的普通文件
     * @return 不含路径的卷标识与文件标识
     * @throws IOException 无法打开文件、对象不是普通文件或句柄释放失败
     */
    static String readFile(Path file) throws IOException {
        return readObject(file, false);
    }

    private static String readObject(Path path, boolean directory) throws IOException {
        Pointer handle = null;
        try (var attributes = new Memory(8); var identity = new Memory(24)) {
            // FILE_FLAG_BACKUP_SEMANTICS 允许打开目录，OPEN_REPARSE_POINT 禁止跟随根重解析目标。
            handle = Api.KERNEL.CreateFileW(new WString(path.toString()), 0x80, 7, null,
                    3, directory ? 0x02200000 : 0x00200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1) {
                handle = null;
                throw new IOException("无法打开目录对象身份");
            }
            if (!Api.KERNEL.GetFileInformationByHandleEx(handle, 9, attributes, 8)
                    || directory && (attributes.getInt(0) & 0x10) == 0
                    || !directory && (attributes.getInt(0) & 0x10) != 0
                    || (attributes.getInt(0) & 0x400) != 0
                    || !Api.KERNEL.GetFileInformationByHandleEx(handle, 18, identity, 24)) {
                throw new IOException("目录对象身份不可用");
            }
            byte[] value = identity.getByteArray(0, 24);
            boolean identified = false;
            for (int index = 8; index < value.length; index++) {
                if (value[index] != 0) identified = true;
            }
            if (!identified) throw new IOException("目录对象身份为空");
            return HexFormat.of().formatHex(value);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Windows 文件系统对象身份接口不可用", failure);
        } finally {
            if (handle != null && !Api.KERNEL.CloseHandle(handle)) {
                throw new IOException("目录身份句柄释放失败");
            }
        }
    }

    private static final class Api {
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security,
                int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer file, int type, Pointer result, int size);
        boolean CloseHandle(Pointer handle);
    }
}
