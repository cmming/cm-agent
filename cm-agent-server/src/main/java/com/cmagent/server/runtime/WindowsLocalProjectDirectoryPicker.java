package com.cmagent.server.runtime;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

/**
 * 现代Windows原生目录选择原语；不读取目录内容，不接受浏览器或模型传入的根路径。
 *
 * <p>使用IFileOpenDialog的FOS_PICKFOLDERS模式，在调用线程初始化COM STA并同步显示真实系统窗口。
 * 调用方必须提供独占的桌面线程；用户关闭窗口前本调用阻塞，线程中断不能强行终止COM模态窗口。
 * 异步选择请求/关闭后结果作废由宿主管理，不能在Servlet请求线程直接调用。</p>
 *
 * <p>本类不单独开放HTTP或模型入口；本机身份/一次性票据和项目根策略由受控宿主实现，
 * 得到Path不等于已批准读取或执行。仅验证Windows x64，不在其他OS延迟加载原生DLL。</p>
 */
final class WindowsLocalProjectDirectoryPicker {
    /** IFileOpenDialog系统类标识，固定值不是客户端可替换的COM类。 */
    private static final String CLASS_ID = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
    /** IFileOpenDialog接口标识，对应Windows SDK定义的固定vtable。 */
    private static final String INTERFACE_ID = "D57C7288-D4AD-4768-BE02-9D969532D960";
    /** HRESULT_FROM_WIN32(ERROR_CANCELLED)：用户取消是正常空结果。 */
    private static final int CANCELLED = 0x800704c7;
    /** 文件夹、文件系统、路径存在、不改cwd及不加入最近列表，不隐式选择虚拟Shell位置。 */
    private static final int OPTIONS = 0x20 | 0x40 | 0x800 | 0x8 | 0x02000000;

    /**
     * 在真实本机桌面显示目录窗口，所有COM资源由本次调用释放。
     * @return 用户确认的目录；用户取消为空，不创建项目或持久化绑定
     * @throws IOException 无桌面、COM/目录读取或资源关闭失败时抛固定安全异常
     */
    Optional<Path> choose() throws IOException {
        requireDesktop();
        boolean initialized = false;
        Pointer dialog = null;
        Pointer item = null;
        Pointer text = null;
        try (var classId = guid(CLASS_ID); var interfaceId = guid(INTERFACE_ID)) {
            // IFileDialog要求STA；S_FALSE仍须配对CoUninitialize，失败则不能释放未初始化的COM。
            if (Api.OLE.CoInitializeEx(null, 0x2 | 0x4) < 0)
                throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
            initialized = true;
            var result = new PointerByReference();
            int created = Api.OLE.CoCreateInstance(classId, null, 1, interfaceId, result);
            dialog = result.getValue();
            check(created);
            if (dialog == null) throw new LocalProjectNativeException(SELECTION_FAILED);
            check(invoke(dialog, 9, OPTIONS));
            check(invoke(dialog, 17, new WString("CM Agent · 选择本地项目文件夹")));
            check(invoke(dialog, 18, new WString("使用此文件夹")));
            int shown = invoke(dialog, 3, (Pointer) null);
            if (shown == CANCELLED) return Optional.empty();
            check(shown);
            var selected = new PointerByReference();
            int fetched = invoke(dialog, 20, selected);
            item = selected.getValue();
            check(fetched);
            if (item == null) throw new LocalProjectNativeException(SELECTION_FAILED);
            var name = new PointerByReference();
            int named = invoke(item, 5, 0x80058000, name);
            text = name.getValue();
            check(named);
            if (text == null) throw new LocalProjectNativeException(SELECTION_FAILED);
            Path path = Path.of(text.getWideString(0));
            if (!path.isAbsolute() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) throw new LocalProjectNativeException(SELECTION_FAILED);
            return Optional.of(path);
        } catch (LocalProjectNativeException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            // COM/JNA异常可含实现和宿主信息；不附加原始cause或message供后续HTTP序列化。
            throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
        } finally {
            try {
                if (text != null) freeTaskMemory(text);
            } finally {
                try {
                    if (item != null) releaseInterface(item);
                } finally {
                    try {
                        if (dialog != null) releaseInterface(dialog);
                    } finally {
                        if (initialized) uninitialize();
                    }
                }
            }
        }
    }

    /** 每层释放独立包装，嵌套finally继续释放其余资源，不让清理异常泄露原生消息。 */
    private static void freeTaskMemory(Pointer value) throws LocalProjectNativeException {
        try { Api.OLE.CoTaskMemFree(value); }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED); }
    }

    private static void releaseInterface(Pointer value) throws LocalProjectNativeException {
        try { invoke(value, 2); }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED); }
    }

    private static void uninitialize() throws LocalProjectNativeException {
        try { Api.OLE.CoUninitialize(); }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED); }
    }

    /** 显式拒绝服务会话/锁定桌面等不可交互环境；不依据SESSIONNAME变量猜测支持性。 */
    static void requireDesktop() throws LocalProjectNativeException {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8)
            throw new LocalProjectNativeException(DESKTOP_UNAVAILABLE);
        try { checkDesktop(Api.USER); }
        catch (LocalProjectNativeException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(NATIVE_UNAVAILABLE); }
    }

    /** 生产始终使用真实桌面API；包内替身只用于验证失败关闭和句柄配对，不绕过choose的OS门禁。 */
    static void checkDesktop(User user) throws LocalProjectNativeException {
        try {
            Pointer desktop = user.OpenInputDesktop(0, false, 1);
            if (desktop == null) throw new LocalProjectNativeException(DESKTOP_UNAVAILABLE);
            if (!user.CloseDesktop(desktop)) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
        } catch (LocalProjectNativeException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
        }
    }

    /** 固定SDK接口槽位，不接受外部提供接口或函数地址；所有入参生命周期覆盖本次同步调用。 */
    private static int invoke(Pointer self, int slot, Object... arguments) {
        Object[] values = new Object[arguments.length + 1];
        values[0] = self;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        return Function.getFunction(self.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE),
                Function.ALT_CONVENTION).invokeInt(values);
    }

    private static void check(int hresult) throws LocalProjectNativeException {
        if (hresult < 0) throw new LocalProjectNativeException(SELECTION_FAILED);
    }

    /** GUID前三段按Win32小端字段写入，尾部按UUID字节序；内存由调用方try-with-resources持有。 */
    private static Memory guid(String value) {
        UUID id = UUID.fromString(value);
        var memory = new Memory(16);
        memory.setInt(0, (int) (id.getMostSignificantBits() >>> 32));
        memory.setShort(4, (short) (id.getMostSignificantBits() >>> 16));
        memory.setShort(6, (short) id.getMostSignificantBits());
        for (int i = 0; i < 8; i++) memory.setByte(8 + i, (byte) (id.getLeastSignificantBits() >>> (56 - i * 8)));
        return memory;
    }

    /** 非Windows不会触发此类初始化；沿用现有JNA核心，不新增jna-platform依赖。 */
    private static final class Api {
        /** 本线程COM初始化、系统类创建及任务内存释放。 */
        private static final Ole OLE = Native.load("ole32", Ole.class);
        /** 只探测并关闭输入桌面句柄，不修改桌面或安全配置。 */
        private static final User USER = Native.load("user32", User.class);
    }

    /** 最小COM入口，HRESULT保留32位符号以判定失败。 */
    interface Ole extends StdCallLibrary {
        int CoInitializeEx(Pointer reserved, int flags);
        int CoCreateInstance(Pointer classId, Pointer outer, int context, Pointer interfaceId, PointerByReference value);
        void CoTaskMemFree(Pointer value);
        void CoUninitialize();
    }

    /** 只读桌面可用性探测。 */
    interface User extends StdCallLibrary {
        Pointer OpenInputDesktop(int flags, boolean inherit, int access);
        boolean CloseDesktop(Pointer desktop);
    }
}

