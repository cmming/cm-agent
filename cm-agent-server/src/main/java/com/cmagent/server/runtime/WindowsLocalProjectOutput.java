package com.cmagent.server.runtime;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

/**
 * 一次进程的独占标准管道和继承白名单，无后台读取线程。
 * STARTUPINFOEX只继承本次stdin/stdout/stderr，不能以bInheritHandles=true泄漏宿主其他可继承句柄。
 */
final class WindowsLocalProjectOutput implements AutoCloseable {
    /** 三个子句柄和两个宿主读取句柄；均由本对象精确释放。 */
    private Pointer outRead, outWrite, errRead, errWrite, input;
    /** 属性列表与其引用的句柄数组必须活到CreateProcessW完成。 */
    private Memory attributes, inherited;
    /** 属性列表初始化成功才允许调用DeleteProcThreadAttributeList。 */
    private boolean initialized;
    /** 关闭后禁止使用原生读取句柄。 */
    private boolean closed;

    static WindowsLocalProjectOutput create() throws IOException {
        var result = new WindowsLocalProjectOutput();
        boolean ready = false;
        try (var security = new Memory(24); var size = new Memory(8)) {
            security.clear(); security.setInt(0, 24); security.setInt(16, 1);
            var read = new PointerByReference(); var write = new PointerByReference();
            check(Api.K.CreatePipe(read, write, security, 0));
            result.outRead = read.getValue(); result.outWrite = write.getValue();
            read = new PointerByReference(); write = new PointerByReference();
            check(Api.K.CreatePipe(read, write, security, 0));
            result.errRead = read.getValue(); result.errWrite = write.getValue();
            check(Api.K.SetHandleInformation(result.outRead, 1, 0));
            check(Api.K.SetHandleInformation(result.errRead, 1, 0));
            result.input = Api.K.CreateFileW(new WString("NUL"), 0x80000000, 3, security, 3, 0, null);
            if (result.input == null || Pointer.nativeValue(result.input) == -1) {
                result.input = null; throw new LocalProjectNativeException(PROCESS_START_FAILED);
            }
            size.clear();
            Api.K.InitializeProcThreadAttributeList(null, 1, 0, size);
            long needed = size.getLong(0);
            if (needed < 1 || needed > 65536) throw new LocalProjectNativeException(PROCESS_START_FAILED);
            result.attributes = new Memory(needed);
            check(Api.K.InitializeProcThreadAttributeList(result.attributes, 1, 0, size));
            result.initialized = true;
            result.inherited = new Memory(24);
            result.inherited.setPointer(0, result.input);
            result.inherited.setPointer(8, result.outWrite);
            result.inherited.setPointer(16, result.errWrite);
            check(Api.K.UpdateProcThreadAttribute(result.attributes, 0, 0x20002, result.inherited, 24, null, null));
            ready = true;
            return result;
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
        } finally { if (!ready) result.close(); }
    }

    /** STARTUPINFOEX x64布局，标准句柄与属性列表在启动期间由本对象持有。 */
    void configure(Memory startup) {
        startup.setInt(0, 112);
        startup.setInt(60, 0x100);
        startup.setPointer(80, input);
        startup.setPointer(88, outWrite);
        startup.setPointer(96, errWrite);
        startup.setPointer(104, attributes);
    }

    /** CreateProcess成功后立即释放父端写句柄，避免子进程退出后仍无法收到EOF。 */
    void launched() throws IOException {
        closeHandle(outWrite); outWrite = null;
        closeHandle(errWrite); errWrite = null;
        closeHandle(input); input = null;
    }

    /**
     * 单一运行监控者调用，最多消费指定字节，不等待子进程新输出。
     * @param stderr true读取stderr，否则stdout
     * @param maximum 最多4096字节；只是当前轮询预算，累计保留预算由命令服务执行
     * @return 当前可用字节，空表示暂时无数据或管道EOF，不意味着进程终态
     */
    synchronized byte[] poll(boolean stderr, int maximum) throws IOException {
        if (closed || maximum < 1 || maximum > 4096) throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
        Pointer handle = stderr ? errRead : outRead;
        var available = new IntByReference();
        try {
            if (!Api.K.PeekNamedPipe(handle, null, 0, null, available, null)) {
                if (Native.getLastError() == 109) return new byte[0];
                throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
            }
            int count = Math.min(maximum, available.getValue());
            if (count < 1) return new byte[0];
            try (var buffer = new Memory(count)) {
                var read = new IntByReference();
                check(Api.K.ReadFile(handle, buffer, count, read, null));
                if (read.getValue() < 0 || read.getValue() > count) throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
                return buffer.getByteArray(0, read.getValue());
            }
        } catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(PROCESS_QUERY_FAILED); }
    }

    private static void check(boolean value) throws LocalProjectNativeException {
        if (!value) throw new LocalProjectNativeException(PROCESS_START_FAILED);
    }
    private static void closeHandle(Pointer value) throws LocalProjectNativeException {
        if (value != null && !Api.K.CloseHandle(value)) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean failed = false;
        for (Pointer handle : new Pointer[]{outRead, outWrite, errRead, errWrite, input}) {
            try { closeHandle(handle); } catch (RuntimeException | LinkageError | IOException failure) { failed = true; }
        }
        try { if (initialized) Api.K.DeleteProcThreadAttributeList(attributes); }
        catch (RuntimeException | LinkageError failure) { failed = true; }
        if (inherited != null) inherited.close();
        if (attributes != null) attributes.close();
        if (failed) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
    }

    private static final class Api {
        /** 仅Windows捕获分支加载；其他平台不触发kernel32。 */
        private static final Kernel K = Native.load("kernel32", Kernel.class);
    }
    interface Kernel extends StdCallLibrary {
        boolean CreatePipe(PointerByReference read, PointerByReference write, Pointer security, int size);
        boolean SetHandleInformation(Pointer handle, int mask, int flags);
        Pointer CreateFileW(WString file, int access, int share, Pointer security, int creation, int flags, Pointer template);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, Pointer size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, long attribute, Pointer value, long size, Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean PeekNamedPipe(Pointer handle, Pointer buffer, int size, IntByReference read, IntByReference available, Pointer left);
        boolean ReadFile(Pointer handle, Pointer buffer, int size, IntByReference read, Pointer overlapped);
        boolean CloseHandle(Pointer handle);
    }
}

