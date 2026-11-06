package com.cmagent.localclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用 Linux Secret Service 的 {@code secret-tool} 命令保存本客户端凭据。
 *
 * <p>秘密以 Base64 字节通过标准输入传递，绝不放入进程参数或环境变量；只查询包含本应用和
 * 随机设备引用的条目。无可用桌面密钥服务时抛出受控异常，由调用方明确提示用户选择口令回退。</p>
 */
final class LinuxSecretToolStore implements SystemCredentialStore {
    private static final String APPLICATION = "cm-agent-local-client";
    private static final int MAX_SECRET_BYTES = 2560;
    private final Runner runner;

    /**
     * @param executable 由部署者或可信平台配置提供的绝对可执行路径，不从请求或模型解析 PATH
     */
    LinuxSecretToolStore(Path executable) {
        if (executable == null || !executable.isAbsolute() || !Files.isRegularFile(executable)
                || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException("系统凭据工具不可用");
        }
        this.runner = new ProcessRunner(executable);
    }

    /** 供隔离测试替换进程调用，不注册生产全局状态。 */
    LinuxSecretToolStore(Runner runner) { this.runner = Objects.requireNonNull(runner); }

    @Override
    public String create(byte[] secret) throws IOException {
        Objects.requireNonNull(secret, "设备秘密不能为空");
        if (secret.length < 1 || secret.length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("设备秘密长度无效");
        }
        String reference = UUID.randomUUID().toString();
        byte[] encoded = Base64.getEncoder().encode(secret);
        try {
            var result = runner.run(List.of("store", "--label=CM Agent Device", "application", APPLICATION,
                    "device", reference), encoded);
            try {
                if (result.exitCode() != 0 || result.errorOutput()) throw new IOException("系统凭据保存失败");
                return reference;
            } finally {
                Arrays.fill(result.output(), (byte) 0);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    @Override
    public Optional<byte[]> read(String reference) throws IOException {
        validateReference(reference);
        var result = runner.run(List.of("lookup", "application", APPLICATION, "device", reference), new byte[0]);
        byte[] output = result.output();
        try {
            if (result.exitCode() != 0) {
                // secret-tool 的无匹配返回非零且没有错误输出；有错误输出时不能误报为无凭据。
                if (!result.errorOutput() && output.length == 0) return Optional.empty();
                throw new IOException("系统凭据读取失败");
            }
            if (result.errorOutput() || output.length == 0) throw new IOException("系统凭据读取失败");
            try {
                byte[] decoded = Base64.getDecoder().decode(output);
                if (decoded.length < 1 || decoded.length > MAX_SECRET_BYTES) {
                    Arrays.fill(decoded, (byte) 0);
                    throw new IOException("系统凭据内容无效");
                }
                return Optional.of(decoded);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("系统凭据内容无效");
            }
        } finally {
            Arrays.fill(output, (byte) 0);
        }
    }

    @Override
    public boolean delete(String reference) throws IOException {
        validateReference(reference);
        Optional<byte[]> existing = read(reference);
        if (existing.isEmpty()) return false;
        Arrays.fill(existing.orElseThrow(), (byte) 0);
        var result = runner.run(List.of("clear", "application", APPLICATION, "device", reference), new byte[0]);
        try {
            if (result.exitCode() != 0 || result.errorOutput()) throw new IOException("系统凭据删除失败");
            return true;
        } finally {
            Arrays.fill(result.output(), (byte) 0);
        }
    }

    private static void validateReference(String reference) {
        try {
            if (reference == null || !UUID.fromString(reference).toString().equals(reference)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("设备凭据引用无效");
        }
    }

    /** 封装进程调用以隔离测试；输入中的秘密只能经标准输入传递。 */
    interface Runner {
        Result run(List<String> arguments, byte[] standardInput) throws IOException;
    }

    /**
     * 一次系统工具调用的受控结果。
     *
     * @param exitCode 工具真实退出码
     * @param output 有界标准输出，可能包含秘密，调用方必须及时清除
     * @param errorOutput 标准错误是否存在内容，不保存其正文
     */
    record Result(int exitCode, byte[] output, boolean errorOutput) {
        Result { Objects.requireNonNull(output, "标准输出不能为空"); }
    }

    /** 拥有单次子进程与管道；超时后停止该子进程，不保存或输出原始错误文本。 */
    private static final class ProcessRunner implements Runner {
        private static final int OUTPUT_LIMIT = 8192;
        private final Path executable;

        private ProcessRunner(Path executable) { this.executable = executable; }

        @Override public Result run(List<String> arguments, byte[] standardInput) throws IOException {
            var command = new java.util.ArrayList<String>();
            command.add(executable.toString());
            command.addAll(arguments);
            Process process = new ProcessBuilder(command).start();
            var stdout = new FutureTask<>(() -> readOutput(process.getInputStream(), OUTPUT_LIMIT));
            var stderr = new FutureTask<>(() -> readOutput(process.getErrorStream(), 1));
            Thread.ofVirtual().start(stdout);
            Thread.ofVirtual().start(stderr);
            byte[] output = null;
            boolean returned = false;
            try {
                try (var input = process.getOutputStream()) { input.write(standardInput); }
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("系统凭据工具响应超时");
                }
                output = stdout.get(1, TimeUnit.SECONDS);
                byte[] error = stderr.get(1, TimeUnit.SECONDS);
                boolean hasError = error.length != 0;
                Arrays.fill(error, (byte) 0);
                returned = true;
                return new Result(process.exitValue(), output, hasError);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("系统凭据操作已中断", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                process.destroyForcibly();
                throw new IOException("系统凭据工具读取失败", failure);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                if (!returned && output != null) Arrays.fill(output, (byte) 0);
            }
        }

        private static byte[] readOutput(InputStream input, int limit) throws IOException {
            byte[] buffer = new byte[1024];
            byte[] output = new byte[limit];
            try (input) {
                int count;
                int size = 0;
                boolean exceeded = false;
                while ((count = input.read(buffer)) != -1) {
                    int accepted = Math.min(count, limit - size);
                    System.arraycopy(buffer, 0, output, size, accepted);
                    size += accepted;
                    if (accepted < count) exceeded = true;
                }
                if (exceeded && limit > 1) throw new IOException("系统凭据输出超出上限");
                return Arrays.copyOf(output, size);
            } finally {
                Arrays.fill(buffer, (byte) 0);
                Arrays.fill(output, (byte) 0);
            }
        }
    }
}
