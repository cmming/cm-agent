package com.cmagent.server.runtime;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

/**
 * Windows同句柄的有界文本读取原语，不使用Java路径检查后再打开的句柄读取根外对象。
 *
 * <p>只打开磁盘普通文件，拒绝重解析/硬链接；最终路径、大小/时间/身份在同一只读句柄前后核验。
 * 不共享写入，已有写句柄会使本次打开失败；共享删除以兼容编辑器原子替换，但名称变化会拒绝交付。
 * 根和祖先策略仍由调用者验证，此组件不授予任何项目权限，也不是OS沙箱。</p>
 */
final class WindowsLocalProjectTextReader {
    /** 映射网络盘也可能显示NTFS，不能只靠FileStore类型判定本地磁盘。 */
    static void requireLocalDrive(Path directory) throws LocalProjectFileException {
        try {
            int type = Api.KERNEL.GetDriveTypeW(new WString(directory.getRoot().toString()));
            if (type == 0 || type == 1) throw new LocalProjectFileException(UNAVAILABLE);
            if (type != 2 && type != 3 && type != 6) throw new LocalProjectFileException(EXCLUDED);
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectFileException(UNAVAILABLE); }
    }

    /**
     * @param expected 已由宿主验证的绝对根内路径，不从HTTP或模型直接获取
     * @param limit 最大实际字节数
     * @param deadline 单调时钟协作期限；不能抢占内核中阻塞的单次调用
     * @return 完整文件的有界字节，成功前不交付任何部分结果
     * @throws LocalProjectFileException 固定安全原因，不带原始Win32消息
     */
    byte[] read(Path expected, int limit, long deadline) throws LocalProjectFileException {
        Pointer handle = null;
        try (var buffer = new Memory(4096)) {
            budget(deadline);
            handle = Api.KERNEL.CreateFileW(new WString(expected.toString()), 0x81, 5, null, 3, 0x00200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1) {
                handle = null;
                int error = Native.getLastError();
                throw new LocalProjectFileException(error == 32 ? FILE_BUSY : error == 5 ? ACCESS_DENIED
                        : error == 2 || error == 3 ? NOT_FOUND : UNAVAILABLE);
            }
            if (Api.KERNEL.GetFileType(handle) != 1) throw new LocalProjectFileException(UNSAFE_FILE);
            var before = metadata(handle, limit);
            verifyPath(handle, expected);
            try (var output = new ProtectedOutput()) {
                var count = new IntByReference();
                while (true) {
                    budget(deadline);
                    if (!Api.KERNEL.ReadFile(handle, buffer, 4096, count, null)) throw new LocalProjectFileException(UNAVAILABLE);
                    int length = count.getValue();
                    if (length == 0) break;
                    if (length < 0 || length > 4096 || length > limit - output.size())
                        throw new LocalProjectFileException(LIMIT_EXCEEDED);
                    output.write(buffer.getByteArray(0, length), 0, length);
                }
                if (!before.equals(metadata(handle, limit))) throw new LocalProjectFileException(FILE_CHANGED);
                verifyPath(handle, expected);
                budget(deadline);
                return output.toByteArray();
            } finally { buffer.clear(); }
        } catch (LocalProjectFileException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectFileException(UNAVAILABLE);
        } finally {
            if (handle != null) {
                try {
                    if (!Api.KERNEL.CloseHandle(handle)) throw new LocalProjectFileException(UNAVAILABLE);
                } catch (RuntimeException | LinkageError failure) { throw new LocalProjectFileException(UNAVAILABLE); }
            }
        }
    }

    /** FILE_STANDARD_INFO和FILE_BASIC_INFO布局来自SDK；不使用访问时间，避免读取自身使核验变化。 */
    private static String metadata(Pointer handle, int limit) throws LocalProjectFileException {
        try (var standard = new Memory(24); var basic = new Memory(40); var identity = new Memory(24)) {
            if (!Api.KERNEL.GetFileInformationByHandleEx(handle, 1, standard, 24)
                    || !Api.KERNEL.GetFileInformationByHandleEx(handle, 0, basic, 40)
                    || !Api.KERNEL.GetFileInformationByHandleEx(handle, 18, identity, 24))
                throw new LocalProjectFileException(UNAVAILABLE);
            int attributes = basic.getInt(32);
            if ((attributes & (0x10 | 0x400)) != 0 || standard.getInt(16) != 1
                    || standard.getByte(20) != 0 || standard.getByte(21) != 0)
                throw new LocalProjectFileException(UNSAFE_FILE);
            long size = standard.getLong(8);
            if (size < 0 || size > limit) throw new LocalProjectFileException(LIMIT_EXCEEDED);
            return HexFormat.of().formatHex(identity.getByteArray(0, 24)) + ":" + basic.getLong(0)
                    + ":" + basic.getLong(16) + ":" + basic.getLong(24) + ":" + size;
        }
    }

    /** 规范化DOS最终路径可发现中间链接及8.3别名，不信任调用前字符串检查。 */
    private static void verifyPath(Pointer handle, Path expected) throws LocalProjectFileException {
        char[] value = new char[32768];
        int length = Api.KERNEL.GetFinalPathNameByHandleW(handle, value, value.length, 0);
        if (length < 1 || length >= value.length) throw new LocalProjectFileException(UNAVAILABLE);
        String actual = new String(value, 0, length);
        if (actual.startsWith("\\\\?\\")) actual = actual.substring(4);
        if (!Path.of(actual).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize()))
            throw new LocalProjectFileException(FILE_CHANGED);
    }

    static void budget(long deadline) throws LocalProjectFileException {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
            throw new LocalProjectFileException(TIMEOUT);
    }

    /** 当前调用持有的明文有界缓冲；结果复制后擦除内部数组，不写临时文件。 */
    private static final class ProtectedOutput extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }

    /** 只有Windows分支调用，不影响其他平台类加载。 */
    private static final class Api {
        /** 仅只读文件与句柄元数据，不提供写入或任意设备访问接口。 */
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    interface Kernel extends StdCallLibrary {
        int GetDriveTypeW(WString root);
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        int GetFileType(Pointer file);
        boolean GetFileInformationByHandleEx(Pointer file, int type, Pointer result, int size);
        int GetFinalPathNameByHandleW(Pointer file, char[] result, int characters, int flags);
        boolean ReadFile(Pointer file, Pointer result, int count, IntByReference read, Pointer overlapped);
        boolean CloseHandle(Pointer handle);
    }
}

