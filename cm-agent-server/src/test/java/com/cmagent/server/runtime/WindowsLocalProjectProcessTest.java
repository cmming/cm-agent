package com.cmagent.server.runtime;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.DataInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

class WindowsLocalProjectProcessTest {
    @TempDir Path temp;

    @Test void 引用规则保留空参数双引号和末尾反斜杠() {
        assertThat(WindowsLocalProjectProcess.quote("")).isEqualTo("\"\"");
        assertThat(WindowsLocalProjectProcess.quote("中文 空格")).isEqualTo("\"中文 空格\"");
        assertThat(WindowsLocalProjectProcess.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(WindowsLocalProjectProcess.quote("a\\")).isEqualTo("\"a\\\\\"");
    }

    @Test void 启动清单拒绝脚本入口及非法环境并隐藏参数() throws Exception {
        Path exe = Files.writeString(temp.resolve("safe.exe"), "仅测试参数验证");
        var launch = new WindowsLocalProjectProcess.Launch(exe, List.of("不能打印的合成参数"), temp,
                Map.of("PRIVATE_TEST", "不能打印的合成环境"));
        assertThat(launch.toString()).doesNotContain("合成参数", "合成环境", temp.toString());
        assertThatThrownBy(() -> new WindowsLocalProjectProcess.Launch(exe, List.of("a\0b"), temp, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("启动参数含不允许的字符");
        assertThatThrownBy(() -> new WindowsLocalProjectProcess.Launch(exe, List.of(), temp, Map.of("A", "1", "a", "2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("子进程环境字段无效");
        Path script = Files.writeString(temp.resolve("build.cmd"), "测试占位，不执行");
        assertThatThrownBy(() -> new WindowsLocalProjectProcess.Launch(script, List.of(), temp, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("启动程序或工作目录无效");
        assertThatThrownBy(() -> WindowsLocalProjectProcess.commandLine(new WindowsLocalProjectProcess.Launch(
                exe, List.of("x".repeat(32767)), temp, Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("启动参数超过系统长度限制");
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 真实Java进程逐字收到参数且不继承宿主PATH() throws Exception {
        Path result = temp.resolve("参数 结果.bin");
        List<String> arguments = List.of("", "中文 空格", "a\"b", "尾部\\", "& echo 不执行", "a\\\\\"b");
        var command = new ArrayList<>(List.of("args", result.toString()));
        command.addAll(arguments);
        try (var running = new WindowsLocalProjectProcess().start(launch(command))) {
            awaitExit(running);
            assertThat(running.exitCode()).isZero();
        }
        try (var input = new DataInputStream(Files.newInputStream(result))) {
            assertThat(input.readUTF()).isEqualTo("仅合成允许值");
            assertThat(input.readBoolean()).isTrue();
            assertThat(input.readInt()).isEqualTo(arguments.size());
            for (String argument : arguments) assertThat(input.readUTF()).isEqualTo(argument);
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 停止整个Job且不会终止不属于本Job的程序() throws Exception {
        Path childFile = temp.resolve("child.pid"), outsideFile = temp.resolve("outside.pid");
        var outsideBuilder = new ProcessBuilder(java(), "-cp", fixtureClassPath(),
                LocalProjectProcessFixture.class.getName(), "sleep", outsideFile.toString());
        outsideBuilder.environment().clear();
        outsideBuilder.environment().putAll(environment());
        Process outside = outsideBuilder.start();
        try (var running = new WindowsLocalProjectProcess().start(launch(List.of("parent", childFile.toString())))) {
            long child = awaitPid(childFile);
            assertThat(running.alive()).isTrue();
            running.stop(Duration.ofSeconds(5));
            assertThat(running.alive()).isFalse();
            assertThat(alive(child)).isFalse();
            assertThat(alive(running.pid())).isFalse();
            assertThat(outside.isAlive()).isTrue();
            running.stop(Duration.ofSeconds(5));
            running.close();
            running.close();
        } finally {
            outside.destroyForcibly();
            assertThat(outside.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 主进程先退出仍不能将存活后代误判为执行完成() throws Exception {
        Path childFile = temp.resolve("orphan.pid");
        long child;
        try (var running = new WindowsLocalProjectProcess().start(launch(List.of("parent-exit", childFile.toString())))) {
            child = awaitPid(childFile);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (alive(running.pid()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(alive(running.pid())).isFalse();
            assertThat(running.alive()).isTrue();
            assertThatThrownBy(running::exitCode).isInstanceOfSatisfying(LocalProjectNativeException.class,
                    error -> assertThat(error.reason()).isEqualTo(PROCESS_QUERY_FAILED));
        }
        assertThat(alive(child)).isFalse();
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 纳管失败时挂起主线程未执行且精确进程被清理() throws Exception {
        Path marker = temp.resolve("must-not-run.bin");
        var pid = new AtomicLong();
        var api = proxy((name, arguments) -> name.equals("AssignProcessToJobObject") ? false : null, pid);
        assertThatThrownBy(() -> new WindowsLocalProjectProcess(api).start(launch(List.of("args", marker.toString()))))
                .isInstanceOfSatisfying(LocalProjectNativeException.class,
                        error -> assertThat(error.reason()).isEqualTo(PROCESS_START_FAILED));
        assertThat(pid.get()).isPositive();
        assertThat(alive(pid.get())).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 恢复线程失败时不会留下挂起或运行的进程() throws Exception {
        Path marker = temp.resolve("resume.bin");
        var pid = new AtomicLong();
        var api = proxy((name, arguments) -> name.equals("ResumeThread") ? -1 : null, pid);
        assertThatThrownBy(() -> new WindowsLocalProjectProcess(api).start(launch(List.of("args", marker.toString()))))
                .isInstanceOf(LocalProjectNativeException.class);
        assertThat(alive(pid.get())).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 终止失败不伪报成功且保留句柄供后续关闭() throws Exception {
        var once = new AtomicBoolean(true);
        var api = proxy((name, arguments) -> name.equals("TerminateJobObject") && once.getAndSet(false) ? false : null,
                new AtomicLong());
        Path childFile = temp.resolve("stop.pid");
        try (var running = new WindowsLocalProjectProcess(api).start(launch(List.of("parent", childFile.toString())))) {
            long child = awaitPid(childFile);
            assertThatThrownBy(() -> running.stop(Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(LocalProjectNativeException.class,
                            error -> assertThat(error.reason()).isEqualTo(PROCESS_STOP_FAILED));
            assertThat(running.alive()).isTrue();
            running.stop(Duration.ofSeconds(5));
            assertThat(alive(child)).isFalse();
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 状态查询失败不误报进程已经结束() throws Exception {
        var once = new AtomicBoolean(true);
        var api = proxy((name, arguments) -> name.equals("QueryInformationJobObject") && once.getAndSet(false) ? false : null,
                new AtomicLong());
        try (var running = new WindowsLocalProjectProcess(api).start(launch(List.of("sleep", temp.resolve("query.pid").toString())))) {
            assertThatThrownBy(running::alive).isInstanceOfSatisfying(LocalProjectNativeException.class,
                    error -> assertThat(error.reason()).isEqualTo(PROCESS_QUERY_FAILED));
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 无效程序启动失败不泄漏程序路径和参数() throws Exception {
        Path invalid = Files.writeString(temp.resolve("invalid.exe"), "不是程序");
        var spec = new WindowsLocalProjectProcess.Launch(invalid, List.of("SECRET_TEST_PAYLOAD"), temp, environment());
        assertThatThrownBy(() -> new WindowsLocalProjectProcess().start(spec))
                .isInstanceOf(LocalProjectNativeException.class)
                .hasMessage("本机进程未能安全启动")
                .hasNoCause();
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 在批准目录完成真实Java编译且自然退出() throws Exception {
        Files.writeString(temp.resolve("Hello.java"), "public class Hello { public static void main(String[] args) {} }");
        var spec = new WindowsLocalProjectProcess.Launch(
                Path.of(System.getProperty("java.home"), "bin", "javac.exe"), List.of("Hello.java"), temp, environment());
        try (var running = new WindowsLocalProjectProcess().start(spec)) {
            awaitExit(running);
            assertThat(running.exitCode()).isZero();
        }
        assertThat(temp.resolve("Hello.class")).isRegularFile();
    }

    private WindowsLocalProjectProcess.Launch launch(List<String> arguments) {
        var all = new ArrayList<>(List.of("-cp", fixtureClassPath(), LocalProjectProcessFixture.class.getName()));
        all.addAll(arguments);
        return new WindowsLocalProjectProcess.Launch(Path.of(java()), all, temp, environment());
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(); }
    private static String fixtureClassPath() {
        try { return Path.of(LocalProjectProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception failure) { throw new IllegalStateException("测试夹具目录不可用"); }
    }
    private static Map<String, String> environment() {
        return Map.of("SystemRoot", System.getenv("SystemRoot"), "CM_AGENT_T1_APPROVED", "仅合成允许值");
    }
    private static boolean alive(long pid) { return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }
    private static long awaitPid(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((!Files.exists(file) || Files.size(file) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
        return Long.parseLong(Files.readString(file));
    }
    private static void awaitExit(WindowsLocalProjectProcess.Running running) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (running.alive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(running.alive()).isFalse();
    }
    /** 只替换一个原生边界，其余真实执行，以证明纳管/停止故障的资源和副作用边界。 */
    private static WindowsLocalProjectProcess.Kernel proxy(Override override, AtomicLong pid) {
        var real = Native.load("kernel32", WindowsLocalProjectProcess.Kernel.class);
        return (WindowsLocalProjectProcess.Kernel) Proxy.newProxyInstance(
                WindowsLocalProjectProcess.Kernel.class.getClassLoader(), new Class<?>[]{WindowsLocalProjectProcess.Kernel.class},
                (object, method, arguments) -> {
                    Object substituted = override.call(method.getName(), arguments);
                    if (substituted != null) return substituted;
                    try {
                        Object value = method.invoke(real, arguments);
                        if (method.getName().equals("CreateProcessW") && Boolean.TRUE.equals(value))
                            pid.set(Integer.toUnsignedLong(((Pointer) arguments[9]).getInt(16)));
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    @FunctionalInterface private interface Override {
        Object call(String name, Object[] arguments);
    }
}

