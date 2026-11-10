package com.cmagent.localclient;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Windows x64 的 IFileOpenDialog；每次调用独立持有并释放 COM 资源。 */
final class WindowsClientDirectoryPicker {
    private static final int CANCELLED = 0x800704c7;
    private static final int OPTIONS = 0x20 | 0x40 | 0x800 | 0x8 | 0x02000000;

    /** 在当前轮询线程的 COM STA 中显示系统窗口，到期由同一消息循环关闭。 */
    Optional<Path> choose(ClientDirectoryPicker.Request request) {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8) {
            throw new IllegalStateException("当前平台尚不支持系统目录窗口");
        }
        requireDesktop();
        boolean initialized = false;
        Pointer dialog = null, item = null, text = null;
        ScheduledExecutorService watchdog = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        try (Memory classId = guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
             Memory interfaceId = guid("D57C7288-D4AD-4768-BE02-9D969532D960")) {
            // IFileDialog 是单线程 COM 对象，不能从看门狗线程直接调用其接口。
            check(Api.OLE.CoInitializeEx(null, 0x2 | 0x4));
            initialized = true;
            var created = new PointerByReference();
            check(Api.OLE.CoCreateInstance(classId, null, 1, interfaceId, created));
            dialog = created.getValue();
            if (dialog == null) throw new IllegalStateException("无法创建目录窗口");
            check(invoke(dialog, 9, OPTIONS));
            String title = "CM Agent · 选择项目文件夹 · " + request.taskId();
            check(invoke(dialog, 17, new WString(title)));
            check(invoke(dialog, 18, new WString("使用此文件夹")));
            watchdog = watchdog(title, request, timedOut);
            if (!request.expiresAt().isAfter(Instant.now())) return Optional.empty();
            int result = invoke(dialog, 3, (Pointer) null);
            if (result == CANCELLED || timedOut.get() || !request.expiresAt().isAfter(Instant.now())) {
                return Optional.empty();
            }
            check(result);
            var selected = new PointerByReference();
            check(invoke(dialog, 20, selected));
            item = selected.getValue();
            if (item == null) throw new IllegalStateException("未取得目录");
            var name = new PointerByReference();
            check(invoke(item, 5, 0x80058000, name));
            text = name.getValue();
            if (text == null) throw new IllegalStateException("未取得目录");
            Path path = Path.of(text.getWideString(0));
            if (!path.isAbsolute() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) throw new IllegalStateException("选择目录不可用");
            return request.expiresAt().isAfter(Instant.now()) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException | LinkageError error) {
            // 原生异常可能包含路径或宿主细节，调用者仅获得固定安全提示。
            throw new IllegalStateException("系统目录窗口不可用");
        } finally {
            // 即使前一项释放失败，也必须尝试释放后续 COM 资源。
            try {
                if (watchdog != null) watchdog.shutdownNow();
            } finally {
                try {
                    if (text != null) Api.OLE.CoTaskMemFree(text);
                } finally {
                    try {
                        if (item != null) invoke(item, 2);
                    } finally {
                        try {
                            if (dialog != null) invoke(dialog, 2);
                        } finally {
                            if (initialized) Api.OLE.CoUninitialize();
                        }
                    }
                }
            }
        }
    }

    /** 使用系统确认框展示配对来源和任务编号，用户拒绝或到期均不进入选择器。 */
    boolean confirm(ClientDirectoryPicker.Request request) {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8) {
            throw new IllegalStateException("当前平台尚不支持系统目录窗口");
        }
        requireDesktop();
        if (!request.expiresAt().isAfter(Instant.now())) return false;
        String title = "CM Agent · 确认目录请求 · " + request.taskId();
        String message = "配对服务端：" + request.serverOrigin() + "\n本机设备："
                + request.deviceName() + "\n请求编号：" + request.taskId()
                + "\n\n请核对项目页中的请求编号。确认后将打开系统文件夹选择窗口。";
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledExecutorService watcher = watchdog(title, request, timedOut);
        try {
            if (!request.expiresAt().isAfter(Instant.now())) return false;
            int choice = Api.USER.MessageBoxW(null, new WString(message), new WString(title),
                    0x1 | 0x20 | 0x10000 | 0x40000);
            return choice == 1 && !timedOut.get() && request.expiresAt().isAfter(Instant.now());
        } finally {
            watcher.shutdownNow();
        }
    }

    private static void requireDesktop() {
        Pointer desktop = Api.USER.OpenInputDesktop(0, false, 1);
        if (desktop == null) throw new IllegalStateException("当前没有可用交互桌面");
        if (!Api.USER.CloseDesktop(desktop)) throw new IllegalStateException("无法释放桌面句柄");
    }

    /** 仅按本次请求的完整编号和本进程标识关闭窗口，不操作其他应用的窗口。 */
    private static ScheduledExecutorService watchdog(String title, ClientDirectoryPicker.Request request,
            AtomicBoolean timedOut) {
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "cm-agent-selection-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleAtFixedRate(() -> {
            if (!request.expiresAt().isAfter(Instant.now())) {
                timedOut.set(true);
                Pointer window = Api.USER.FindWindowW(null, new WString(title));
                if (window != null) {
                    IntByReference owner = new IntByReference();
                    Api.USER.GetWindowThreadProcessId(window, owner);
                    if (Integer.toUnsignedLong(owner.getValue()) == ProcessHandle.current().pid())
                        Api.USER.PostMessageW(window, 0x0010, null, null);
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return watcher;
    }

    private static int invoke(Pointer self, int slot, Object... arguments) {
        Object[] values = new Object[arguments.length + 1];
        values[0] = self;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        return Function.getFunction(self.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE),
                Function.ALT_CONVENTION).invokeInt(values);
    }

    private static void check(int result) {
        if (result < 0) throw new IllegalStateException("系统目录窗口调用失败");
    }

    /** GUID 的前三段使用 Win32 小端表示，尾部保留 UUID 字节序。 */
    private static Memory guid(String value) {
        UUID id = UUID.fromString(value);
        Memory result = new Memory(16);
        result.setInt(0, (int) (id.getMostSignificantBits() >>> 32));
        result.setShort(4, (short) (id.getMostSignificantBits() >>> 16));
        result.setShort(6, (short) id.getMostSignificantBits());
        for (int i = 0; i < 8; i++) result.setByte(8 + i,
                (byte) (id.getLeastSignificantBits() >>> (56 - i * 8)));
        return result;
    }

    private static final class Api {
        private static final Ole OLE = Native.load("ole32", Ole.class);
        private static final User USER = Native.load("user32", User.class);
    }

    private interface Ole extends StdCallLibrary {
        int CoInitializeEx(Pointer reserved, int flags);
        int CoCreateInstance(Pointer classId, Pointer outer, int context, Pointer interfaceId, PointerByReference value);
        void CoTaskMemFree(Pointer value);
        void CoUninitialize();
    }

    private interface User extends StdCallLibrary {
        Pointer OpenInputDesktop(int flags, boolean inherit, int access);
        boolean CloseDesktop(Pointer desktop);
        int MessageBoxW(Pointer owner, WString text, WString caption, int type);
        Pointer FindWindowW(WString className, WString title);
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
        boolean PostMessageW(Pointer window, int message, Pointer wParam, Pointer lParam);
    }
}
