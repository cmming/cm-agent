package com.cmagent.server.runtime;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

/**
 * Windows x64受管进程原语：先挂起创建、纳入独占Job，再恢复主线程。
 *
 * <p>不设置breakaway标志，后代进程继承Job；关闭Job触发整组终止，不按名称扫描或误杀其他进程。
 * 这只提供生命周期管理，不限制进程的OS账号文件/网络权限，不是沙箱。</p>
 *
 * <p>仅提供内部原生进程能力，调用方须先校验项目、精确审批、可执行文件和环境。
 * 可选stdout/stderr经显式句柄白名单和有界管道读取，输出脱敏、截断及恢复由命令服务负责；不能把无输出当命令已完成。</p>
 */
final class WindowsLocalProjectProcess {
    /** Windows x64 STARTUPINFOW/PROCESS_INFORMATION/扩展Job限制的SDK布局大小。 */
    private static final int STARTUP_SIZE = 104, PROCESS_SIZE = 24, LIMIT_SIZE = 144;
    /** 挂起创建、无控制台窗口、显式Unicode环境；不继承宿主句柄或breakaway权限。 */
    private static final int CREATION_FLAGS = 0x4 | 0x08000000 | 0x400;
    /** Win32命令行包含终止符最多32767个UTF-16代码单元。 */
    private static final int MAX_COMMAND = 32766;
    /** 生产延迟原生库入口；测试可注入故障代理验证失败时不会运行未受管子进程。 */
    private final Kernel override;

    WindowsLocalProjectProcess() { this(null); }
    WindowsLocalProjectProcess(Kernel override) { this.override = override; }
    private Kernel api() { return override == null ? Api.KERNEL : override; }

    /**
     * 创建并返回已纳入Job的进程；调用方必须关闭返回值，结束主进程不代表后代全部结束。
     * @param launch 已由上层授权的精确启动数据，本方法不实现工具链或项目授权
     * @return 独占本次Job和主进程句柄的运行对象
     * @throws IOException 创建、纳管、恢复或资源释放失败；失败不会返回可继续执行的对象
     */
    Running start(Launch launch) throws IOException {
        return start(launch, false);
    }

    /** 捕获模式仅继承本次标准管道，必须由单一监控者持续poll以免子进程管道写满。 */
    Running start(Launch launch, boolean capture) throws IOException {
        Objects.requireNonNull(launch, "启动数据不能为空");
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8)
            throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
        String command = commandLine(launch);
        Pointer job = null, process = null, thread = null;
        boolean transferred = false;
        WindowsLocalProjectOutput output = capture ? WindowsLocalProjectOutput.create() : null;
        try {
            Running ready;
            try (var limits = new Memory(LIMIT_SIZE); var startup = new Memory(capture ? 112 : STARTUP_SIZE);
                var information = new Memory(PROCESS_SIZE); var environment = environment(launch.environment())) {
            job = api().CreateJobObjectW(null, null);
            if (job == null) throw new LocalProjectNativeException(PROCESS_START_FAILED);
            limits.clear();
            // JOBOBJECT_BASIC_LIMIT_INFORMATION.LimitFlags在x64 SDK布局的偏移16；
            // 只设KILL_ON_JOB_CLOSE，不允许任何成员主动breakaway。
            limits.setInt(16, 0x2000);
            if (!api().SetInformationJobObject(job, 9, limits, LIMIT_SIZE))
                throw new LocalProjectNativeException(PROCESS_START_FAILED);
            startup.clear(); startup.setInt(0, STARTUP_SIZE); information.clear();
            if (output != null) output.configure(startup);
            char[] buffer = (command + "\0").toCharArray();
            if (!api().CreateProcessW(new WString(launch.executable().toString()), buffer, null, null,
                    capture, CREATION_FLAGS | (capture ? 0x80000 : 0), environment, new WString(launch.directory().toString()), startup, information))
                throw new LocalProjectNativeException(PROCESS_START_FAILED);
            process = information.getPointer(0); thread = information.getPointer(8);
            if (output != null) output.launched();
            if (process == null || thread == null || !api().AssignProcessToJobObject(job, process))
                throw new LocalProjectNativeException(PROCESS_START_FAILED);
            if (api().ResumeThread(thread) != 1) throw new LocalProjectNativeException(PROCESS_START_FAILED);
            if (!api().CloseHandle(thread)) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
            thread = null;
            ready = new Running(api(), job, process, Integer.toUnsignedLong(information.getInt(16)), output);
            }
            // 临时原生内存成功关闭后才转移句柄；若try-with-resources失败，finally仍须终止刚创建的Job。
            transferred = true;
            return ready;
        } catch (LocalProjectNativeException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectNativeException(NATIVE_UNAVAILABLE);
        } finally {
            if (!transferred) {
                // 纳管前失败时主线程仍挂起：先终止该精确进程，再释放Job；不能留下永远挂起的进程。
                // 纳管后失败同时终止Job后代；失败分类保持固定，不泄露Win32原始消息。
                try { cleanupFailed(job, process, thread); }
                finally { if (output != null) output.close(); }
            }
        }
    }

    private void cleanupFailed(Pointer job, Pointer process, Pointer thread) throws LocalProjectNativeException {
        boolean failed = false;
        try {
            if (job != null && !api().TerminateJobObject(job, 1)) failed = true;
            if (process != null) {
                if (!api().TerminateProcess(process, 1) && api().WaitForSingleObject(process, 0) != 0) failed = true;
                if (api().WaitForSingleObject(process, 5000) != 0) failed = true;
            }
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
        } finally {
            for (Pointer handle : new Pointer[]{thread, process, job}) {
                if (handle != null) {
                    try { if (!api().CloseHandle(handle)) failed = true; }
                    catch (RuntimeException | LinkageError failure) { failed = true; }
                }
            }
        }
        if (failed) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
    }

    /**
     * 启动清单，不接收待隐式拼接的shell命令。
     * @param executable 上层已批准的绝对普通EXE文件；脚本须显式选择获准解释器
     * @param arguments 精确参数数组，不从PATH猜测程序，不进行shell展开
     * @param directory 已批准的绝对工作目录，不意味着限制该账号根外访问
     * @param environment 全量显式子进程环境，不自动继承Server Secret
     */
    record Launch(Path executable, List<String> arguments, Path directory, Map<String, String> environment) {
        Launch {
            Objects.requireNonNull(executable, "可执行文件不能为空");
            Objects.requireNonNull(directory, "工作目录不能为空");
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            if (!executable.isAbsolute() || !Files.isRegularFile(executable)
                    || !executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                    || !directory.isAbsolute() || !Files.isDirectory(directory))
                throw new IllegalArgumentException("启动程序或工作目录无效");
            if (executable.toString().indexOf('\0') >= 0 || directory.toString().indexOf('\0') >= 0
                    || arguments.stream().anyMatch(value -> value.indexOf('\0') >= 0))
                throw new IllegalArgumentException("启动参数含不允许的字符");
            var names = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (var entry : environment.entrySet()) {
                if (entry.getKey().isBlank() || entry.getKey().indexOf('=') >= 0 || entry.getKey().indexOf('\0') >= 0
                        || entry.getValue().indexOf('\0') >= 0 || names.put(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException("子进程环境字段无效");
            }
        }
        /** 不允许record默认toString将参数或环境秘密复制到日志。 */
        @Override public String toString() { return "Launch[受保护的启动清单]"; }
    }

    /** 按Windows CRT参数规则引用空参数、双引号和反斜杠，不执行任何shell语法。 */
    static String commandLine(Launch launch) {
        StringBuilder result = new StringBuilder(quote(launch.executable().toString()));
        for (String argument : launch.arguments()) result.append(' ').append(quote(argument));
        if (result.length() > MAX_COMMAND) throw new IllegalArgumentException("启动参数超过系统长度限制");
        return result.toString();
    }

    static String quote(String argument) {
        StringBuilder value = new StringBuilder("\"");
        int slashes = 0;
        for (char ch : argument.toCharArray()) {
            if (ch == '\\') { slashes++; continue; }
            if (ch == '"') value.append("\\".repeat(slashes * 2 + 1));
            else value.append("\\".repeat(slashes));
            value.append(ch); slashes = 0;
        }
        return value.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    /** CreateProcessW环境块必须按键排序且双NUL结束，空环境也显式传入，禁止null触发宿主继承。 */
    private static Memory environment(Map<String, String> values) {
        var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(values);
        var value = new StringBuilder();
        sorted.forEach((key, text) -> value.append(key).append('=').append(text).append('\0'));
        if (value.isEmpty()) value.append('\0');
        value.append('\0');
        if (value.length() > 32767) throw new IllegalArgumentException("子进程环境超过系统长度限制");
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_16LE);
        var buffer = new Memory(bytes.length);
        buffer.write(0, bytes, 0, bytes.length);
        java.util.Arrays.fill(bytes, (byte) 0);
        return buffer;
    }

    /** 一次受管启动的句柄所有者；所有状态操作串行，关闭幂等且不终止其他Job。 */
    static final class Running implements AutoCloseable {
        /** 本对象创建时固定的原生入口，测试故障替身不会影响其他执行。 */
        private final Kernel kernel;
        /** 独占Job，关闭前负责确认受支持后代退出。 */
        private final Pointer job;
        /** 主进程句柄，只有本对象释放。 */
        private final Pointer process;
        /** 主进程标识仅供可信内部核对，不作为授权或全局终止条件。 */
        private final long pid;
        /** 标志资源已释放，阻止对回收句柄再次调用。 */
        private boolean closed;
        /** 本次标准管道，可为空；在Job退出后仍允许消费尾部，再由close释放。 */
        private final WindowsLocalProjectOutput output;

        private Running(Kernel kernel, Pointer job, Pointer process, long pid, WindowsLocalProjectOutput output) {
            this.kernel = kernel; this.job = job; this.process = process; this.pid = pid;
            this.output = output;
        }
        /** 当前可用输出，不等待新数据；调用方只能安排一个读取者。 */
        synchronized byte[] poll(boolean stderr) throws IOException {
            if (closed || output == null) throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
            return output.poll(stderr, 4096);
        }
        /** @return 主进程ID；不能据此跨运行查询或结束进程 */
        long pid() { return pid; }

        /** @return Job是否仍有任何成员，主进程退出但子进程存活时仍为true */
        synchronized boolean alive() throws IOException {
            if (closed) return false;
            try (var accounting = new Memory(48)) {
                if (!kernel.QueryInformationJobObject(job, 1, accounting, 48, null))
                    throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
                if (accounting.getInt(40) != 0) return true;
                // Job记账清零与主进程对象发出退出信号可能存在极短窗口；两者都确认后才释放cwd等资源。
                // 仅凭ActiveProcesses=0提前结束，会使调用方立即清理目录时遇到仍被占用的路径。
                int wait = kernel.WaitForSingleObject(process, 0);
                if (wait == 0) return false;
                if (wait == 258) return true;
                throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
            } catch (LocalProjectNativeException failure) { throw failure; }
            catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(PROCESS_QUERY_FAILED); }
        }

        /** @return 整组退出后的主进程退出码，仍执行或已关闭时拒绝读取，避免误报完成 */
        synchronized int exitCode() throws IOException {
            try {
                if (closed || alive()) throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
                var result = new IntByReference();
                if (!kernel.GetExitCodeProcess(process, result)) throw new LocalProjectNativeException(PROCESS_QUERY_FAILED);
                return result.getValue();
            } catch (LocalProjectNativeException failure) { throw failure; }
            catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(PROCESS_QUERY_FAILED); }
        }

        /**
         * 终止本Job并有界等待实际后代退出，不回滚它们已经产生的文件或网络副作用。
         * @param timeout 等待上限，必须在0至10秒内；等待期间保留并最终恢复调用线程中断标志
         * @throws IOException 终止失败或期限内仍有成员，调用方不得报告CANCELLED
         */
        synchronized void stop(Duration timeout) throws IOException {
            if (timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
                throw new IllegalArgumentException("进程停止等待时间无效");
            if (closed || !alive()) return;
            try {
                if (!kernel.TerminateJobObject(job, 1)) throw new LocalProjectNativeException(PROCESS_STOP_FAILED);
            } catch (LocalProjectNativeException failure) { throw failure; }
            catch (RuntimeException | LinkageError failure) { throw new LocalProjectNativeException(PROCESS_STOP_FAILED); }
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean interrupted = false;
            try {
                while (alive()) {
                    if (System.nanoTime() >= deadline) throw new LocalProjectNativeException(PROCESS_STOP_FAILED);
                    if (Thread.interrupted()) interrupted = true;
                    LockSupport.parkNanos(10_000_000);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /** 关闭先尝试整组退出，最后释放本对象句柄；任何失败仍向上抛出，不能伪装清理成功。 */
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            IOException failure = null;
            try { stop(Duration.ofSeconds(5)); }
            catch (IOException error) { failure = error; }
            finally {
                closed = true;
                if (output != null) {
                    try { output.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                }
                for (Pointer handle : new Pointer[]{process, job}) {
                    try {
                        if (!kernel.CloseHandle(handle)) throw new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
                    } catch (RuntimeException | LinkageError | LocalProjectNativeException error) {
                        var safe = new LocalProjectNativeException(RESOURCE_RELEASE_FAILED);
                        if (failure == null) failure = safe; else failure.addSuppressed(safe);
                    }
                }
            }
            if (failure != null) throw failure;
        }
    }

    /** 按需加载，不影响其他OS或旧功能启动。 */
    private static final class Api {
        /** 仅限本进程创建的句柄，不暴露OpenProcess或按名称终止。 */
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    /** Windows SDK最小原型；Memory布局仅在明确支持的Windows x64启用。 */
    interface Kernel extends StdCallLibrary {
        Pointer CreateJobObjectW(Pointer security, WString name);
        boolean SetInformationJobObject(Pointer job, int type, Pointer information, int length);
        boolean CreateProcessW(WString executable, char[] command, Pointer processSecurity, Pointer threadSecurity,
                boolean inheritHandles, int flags, Pointer environment, WString directory, Pointer startup, Pointer information);
        boolean AssignProcessToJobObject(Pointer job, Pointer process);
        int ResumeThread(Pointer thread);
        boolean TerminateJobObject(Pointer job, int exitCode);
        boolean TerminateProcess(Pointer process, int exitCode);
        int WaitForSingleObject(Pointer handle, int timeout);
        boolean QueryInformationJobObject(Pointer job, int type, Pointer information, int length, IntByReference returned);
        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);
        boolean CloseHandle(Pointer handle);
    }
}

