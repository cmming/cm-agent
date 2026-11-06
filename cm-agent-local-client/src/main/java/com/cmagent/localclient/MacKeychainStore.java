package com.cmagent.localclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * 使用 macOS 系统钥匙串保存本机设备秘密。
 *
 * <p>调用固定绝对路径 {@code /usr/bin/security}，账户名只使用本应用前缀和随机 UUID。新增条目时
 * 不把秘密放入命令行参数，而是写入进程标准输入；读取和删除也只操作本应用条目。当前环境没有
 * macOS 实机，因此真实钥匙串交互仍需在 macOS 验收。</p>
 */
final class MacKeychainStore implements SystemCredentialStore {
    private static final String SERVICE = "cm-agent-local-client";
    private static final String ACCOUNT_PREFIX = "device-";
    private static final int MAX_SECRET_BYTES = 2560;
    private final Runner runner;

    /**
     * @param executable 固定系统工具路径，不从 PATH 或请求解析
     */
    MacKeychainStore(Path executable) {
        if (executable == null || !executable.isAbsolute() || !Files.isRegularFile(executable)
                || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException("macOS 系统钥匙串工具不可用");
        }
        this.runner = new ProcessRunner(executable);
    }

    /** 供隔离测试替换系统命令。 */
    MacKeychainStore(Runner runner) {
        this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public String create(byte[] secret) throws IOException {
        Objects.requireNonNull(secret, "设备秘密不能为空");
        if (secret.length < 1 || secret.length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("设备秘密长度无效");
        }
        String account = ACCOUNT_PREFIX + UUID.randomUUID();
        byte[] encoded = Base64.getEncoder().encode(secret);
        byte[] input = new byte[encoded.length + 2];
        System.arraycopy(encoded, 0, input, 0, encoded.length);
        input[encoded.length] = '\n';
        input[encoded.length + 1] = '\n';
        try {
            Result result = runner.run(List.of("add-generic-password", "-U", "-a", account,
                    "-s", SERVICE, "-w"), input);
            try {
                if (result.exitCode() != 0 || result.errorOutput()) throw new IOException("系统钥匙串保存失败");
                return account;
            } finally {
                Arrays.fill(result.output(), (byte) 0);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public Optional<byte[]> read(String reference) throws IOException {
        validateReference(reference);
        Result result = runner.run(List.of("find-generic-password", "-w", "-a", reference,
                "-s", SERVICE), new byte[0]);
        byte[] output = result.output();
        try {
            if (result.exitCode() != 0) {
                // 无匹配与系统故障都以非零退出；只有无标准错误和无输出时才按不存在处理。
                if (!result.errorOutput() && output.length == 0) return Optional.empty();
                throw new IOException("系统钥匙串读取失败");
            }
            if (result.errorOutput() || output.length == 0) throw new IOException("系统钥匙串读取失败");
            int length = output.length;
            while (length > 0 && (output[length - 1] == '\n' || output[length - 1] == '\r')) length--;
            byte[] decoded = Base64.getDecoder().decode(Arrays.copyOf(output, length));
            if (decoded.length < 1 || decoded.length > MAX_SECRET_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                throw new IOException("系统钥匙串内容无效");
            }
            return Optional.of(decoded);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("系统钥匙串内容无效");
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
        Result result = runner.run(List.of("delete-generic-password", "-a", reference,
                "-s", SERVICE), new byte[0]);
        try {
            if (result.exitCode() != 0 || result.errorOutput()) throw new IOException("系统钥匙串删除失败");
            return true;
        } finally {
            Arrays.fill(result.output(), (byte) 0);
        }
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.startsWith(ACCOUNT_PREFIX)) {
            throw new IllegalArgumentException("设备凭据引用无效");
        }
        try {
            String suffix = reference.substring(ACCOUNT_PREFIX.length());
            if (!UUID.fromString(suffix).toString().equals(suffix)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("设备凭据引用无效");
        }
    }

    /** 一次系统命令调用，不保留标准错误正文。 */
    interface Runner {
        Result run(List<String> arguments, byte[] standardInput) throws IOException;
    }

    /**
     * @param exitCode 系统工具退出码
     * @param output 有界标准输出；可能含秘密，调用方必须清除
     * @param errorOutput 标准错误是否存在内容
     */
    record Result(int exitCode, byte[] output, boolean errorOutput) {
        Result { Objects.requireNonNull(output); }
    }

    private static final class ProcessRunner implements Runner {
        private static final int OUTPUT_LIMIT = 8192;
        private final Path executable;

        private ProcessRunner(Path executable) { this.executable = executable; }

        @Override
        public Result run(List<String> arguments, byte[] standardInput) throws IOException {
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
                    throw new IOException("系统钥匙串工具响应超时");
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
                throw new IOException("系统钥匙串操作已中断", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                process.destroyForcibly();
                throw new IOException("系统钥匙串输出读取失败", failure);
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
                if (exceeded && limit > 1) throw new IOException("系统钥匙串输出超出上限");
                return Arrays.copyOf(output, size);
            } finally {
                Arrays.fill(buffer, (byte) 0);
                Arrays.fill(output, (byte) 0);
            }
        }
    }
}
