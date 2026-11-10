package com.cmagent.localclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 客户端本机命令原语；当前尚未连接远程请求或智能体工具。
 *
 * <p>命令以客户端进程账号的操作系统权限运行，可以访问该账号可及的文件、网络并启动其他程序。
 * 调用者必须先校验设备、主体、本轮授权和具体命令；本类不是授权边界或操作系统沙箱。</p>
 */
final class LocalCommandOperations {
    /** 单次命令最多保留的合并输出，超额后仍排空管道以防子进程阻塞。 */
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    /**
     * 启动一条以参数列表表达的本机命令。
     *
     * <p>不自动拼接 Shell 字符串；需要 Shell 时必须由已经授权的参数列表明确指定解释器。
     * 输入流立即关闭，不支持交互式命令；合并输出由返回的句柄持有并在关闭时释放。</p>
     *
     * @param arguments 程序及其参数，不得为空
     * @param directory 用户授权的本机绝对工作目录
     * @param outputLimit 本次最多保留的输出字节数
     * @return 持有已启动进程和输出读取资源的句柄
     * @throws IOException 进程未能启动；调用方应输出脱敏原因
     */
    CommandHandle start(List<String> arguments, Path directory, int outputLimit) throws IOException {
        return start(arguments, directory, outputLimit, null);
    }

    /**
     * 使用显式固定环境启动命令，不继承当前客户端进程的任意环境变量。
     *
     * @param arguments 固定程序及精确参数，不自动拼接 Shell
     * @param directory 已核验的项目内绝对工作目录
     * @param environment 完整受控环境；调用方不得传入未经审批的秘密值
     * @param outputLimit 本次最多保留的输出字节数
     * @return 持有已启动进程和输出读取资源的句柄
     */
    CommandHandle start(List<String> arguments, Path directory, Map<String, String> environment,
            int outputLimit) throws IOException {
        Objects.requireNonNull(environment, "命令环境不能为空");
        return start(arguments, directory, outputLimit, environment);
    }

    private CommandHandle start(List<String> arguments, Path directory, int outputLimit,
            Map<String, String> environment) throws IOException {
        Objects.requireNonNull(arguments, "命令参数不能为空");
        if (arguments.isEmpty() || arguments.size() > 128 || arguments.stream().anyMatch(Objects::isNull)
                || arguments.getFirst().isBlank() || arguments.stream().anyMatch(value -> value.length() > 32_768)) {
            throw new IllegalArgumentException("命令参数无效");
        }
        if (directory == null || !directory.isAbsolute() || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("命令工作目录无效");
        }
        if (outputLimit < 1 || outputLimit > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("命令输出上限无效");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(arguments)).directory(directory.toFile())
                .redirectErrorStream(true);
        if (environment != null) {
            // 设备项目固定工具链必须自行声明运行环境和 PATH，不能隐式继承客户端启动环境。
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            return new CommandHandle(process, outputLimit);
        } catch (IOException | RuntimeException failure) {
            // 启动后初始化失败时不能遗留已经获得用户账号权限的进程。
            process.destroyForcibly();
            throw failure;
        }
    }

    /**
     * 一次进程的可观察状态。
     *
     * @param exited 根进程是否已退出；不代表所有脱离进程树的程序均已退出
     * @param exitCode 根进程已退出时的退出码，否则为 {@code null}
     * @param stopRequested 是否已登记尽力停止请求
     * @param output 已接收的有界输出，构造和访问时均复制
     * @param truncated 输出是否超出保留上限
     * @param treeExited 本句柄已观察到的后代进程是否全部退出；根进程退出不能替代该证据
     * @param outputComplete 是否已读到输出流结束；根进程退出不保证此值为真
     */
    record CommandStatus(boolean exited, Integer exitCode, boolean stopRequested,
                         boolean treeExited, byte[] output, boolean truncated,
                         boolean outputComplete) {
        CommandStatus {
            output = Objects.requireNonNull(output, "命令输出不能为空").clone();
        }
        @Override public byte[] output() { return output.clone(); }
    }

    /** 一次命令的独占句柄，由调用者在结束或不再使用时关闭。 */
    static final class CommandHandle implements AutoCloseable {
        private final Process process;
        private final Capture capture;
        private final Thread reader;
        /** 只保留本次根进程下已观察到的后代，便于根退出后继续等待或停止残留进程。 */
        private final java.util.Set<ProcessHandle> observedDescendants =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile boolean stopRequested;

        private CommandHandle(Process process, int limit) {
            this.process = process;
            this.capture = new Capture(limit);
            // 专用虚拟线程持续排空 stdout/stderr，避免输出上限使子进程因管道填满而挂起。
            this.reader = Thread.ofVirtual().name("cm-agent-local-command-output").start(() -> drain(process.getInputStream()));
        }

        /**
         * 等待根进程退出或等待时间耗尽，期间不会自动重启或重试命令。
         *
         * @param wait 最长等待时间，不能为负或超过三十分钟
         * @return 当前根进程及输出快照
         * @throws InterruptedException 等待线程被中断；命令仍可能运行
         */
        CommandStatus await(Duration wait) throws InterruptedException {
            Objects.requireNonNull(wait, "等待时间不能为空");
            if (wait.isNegative() || wait.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("命令等待时间无效");
            }
            observeDescendants();
            boolean exited = process.waitFor(wait.toNanos(), TimeUnit.NANOSECONDS);
            if (exited) {
                // 派生进程可能仍持有管道；最多短暂等待，不能无限阻塞或伪称输出已完整。
                reader.join(Duration.ofSeconds(1));
            }
            observeDescendants();
            boolean treeExited = observedDescendants.stream().noneMatch(ProcessHandle::isAlive);
            return capture.status(exited, exited ? process.exitValue() : null, stopRequested,
                    exited && treeExited);
        }

        /**
         * 登记停止并尽力终止当前可见的子进程和根进程。
         *
         * <p>其他进程可能已脱离进程树或已经产生外部副作用；本方法返回不等于所有子进程
         * 已退出，也不表示副作用已回滚。调用方需继续核对状态，无法确认时保留未知结果。</p>
         */
        void requestStop() {
            stopRequested = true;
            // 只操作本次根进程可见的后代，不扫描或终止用户的其他进程。
            observeDescendants();
            List<ProcessHandle> descendants = observedDescendants.stream()
                    .filter(ProcessHandle::isAlive).toList();
            descendants.reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        private void observeDescendants() {
            process.descendants().forEach(observedDescendants::add);
            observedDescendants.removeIf(value -> !value.isAlive());
        }

        private void drain(InputStream input) {
            try (input) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) capture.add(chunk, count);
                capture.complete();
            } catch (IOException ignored) {
                // 读取异常或关闭不等于完整输出；保留 outputComplete=false 供调用方核对。
            }
        }

        /** 只关闭本句柄持有的进程及管道，不删除文件或影响其他运行。 */
        @Override public void close() {
            if (process.isAlive()) requestStop();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    /** 与输出线程共享的有界快照；不保留超出预算的正文。 */
    private static final class Capture {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean truncated;
        private boolean complete;

        private Capture(int limit) { this.limit = limit; }

        private synchronized void add(byte[] bytes, int count) {
            int accepted = Math.min(count, limit - output.size());
            output.write(bytes, 0, accepted);
            if (accepted < count) truncated = true;
        }

        private synchronized void complete() { complete = true; }

        private synchronized CommandStatus status(boolean exited, Integer exitCode,
                boolean stopRequested, boolean treeExited) {
            return new CommandStatus(exited, exitCode, stopRequested, treeExited,
                    output.toByteArray(), truncated, complete);
        }
    }
}
