package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.server.config.SkillSandboxProperties;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将固定脚本与资源通过 stdin 交给一次性 Linux 容器，不挂载宿主目录或 Docker socket。
 *
 * <p>Docker daemon 和镜像属于部署者可信边界；容器共享宿主内核，生产处理不可信包时应选择
 * 强化 OCI runtime 或专用执行主机。本类不会降级到宿主 Python/Shell，也不拉取镜像。
 * 每次调用独立持有进程和有界输出，超时、中断、异常均按唯一容器名清理。</p>
 */
public class DockerSkillSandbox {
    /** 固定引导程序，模型只能提供 JSON 数据；资源仅写入容器的私有 tmpfs。 */
    private static final String BOOTSTRAP = """
            import json, sys, os, pathlib, io, runpy
            payload = json.load(sys.stdin)
            root = pathlib.Path('/workspace/skill')
            root.mkdir()
            for name, content in payload['files'].items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
            os.chdir(root)
            sys.path.insert(0, str(root))
            sys.stdin = io.StringIO(payload['stdin'])
            sys.argv = [payload['path']]
            runpy.run_path(str(root / payload['path']), run_name='__main__')
            """;
    /** 配置由服务启动期绑定，执行参数不能覆盖。 */
    private final SkillSandboxProperties properties;
    /** 单实例容器限流，不等待额外排队以免延长超时预算。 */
    private final Semaphore permits;
    /** 仅用于固定数据序列化与输出脱敏，不记录原始输入输出。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 成功输出也须脱敏，失败时不会交付 Python traceback。 */
    private final ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(mapper);

    /** @param properties 可信部署策略；实例应在服务生命周期内复用以保持并发限流 */
    public DockerSkillSandbox(SkillSandboxProperties properties) {
        properties.validate();
        this.properties = properties;
        this.permits = new Semaphore(properties.getMaxConcurrent());
    }

    /**
     * 执行已授权固定资源；脚本、资源和输入不会出现在命令参数或日志中。
     *
     * @param request 已校验的可信运行上下文及脚本路径
     * @param files 固定技能版本的资源副本，禁止绝对路径和路径逃逸
     * @param stdin 仅供解释器使用的有界输入
     * @return 脱敏成功输出
     * @throws SkillAccessException 关闭、限额、超时、脚本失败或 Docker 不可用时抛出
     */
    public String execute(SkillReadRequest request, Map<String, String> files, String stdin) {
        if (!properties.isEnabled()) throw failure(request, ApiErrorCode.SKILL_SANDBOX_DISABLED, "技能沙箱未启用");
        if (stdin == null || stdin.getBytes(StandardCharsets.UTF_8).length > properties.getMaxInputBytes()
                || !safePath(request.path()) || !request.path().endsWith(".py")
                || !files.containsKey(request.path()) || files.size() > 65
                || files.keySet().stream().anyMatch(path -> !safePath(path))
                || files.values().stream().mapToLong(text -> text.getBytes(StandardCharsets.UTF_8).length).sum()
                > 4 * 1024 * 1024) {
            throw failure(request, ApiErrorCode.SKILL_SANDBOX_INVALID, "沙箱脚本、资源或输入不合法");
        }
        if (!permits.tryAcquire()) throw failure(request, ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED, "技能沙箱繁忙，请稍后重试");
        String name = "cm-agent-skill-" + java.util.UUID.randomUUID();
        Process process = null;
        boolean launched = false;
        // 独立虚拟线程并行写 stdin 和读 stdout，避免大包/大量输出造成管道互相等待。
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("files", files, "path", request.path(), "stdin", stdin));
            long deadline = System.nanoTime() + properties.getTimeout().toNanos();
            process = start(command(name));
            launched = true;
            Process running = process;
            Future<?> writer = workers.submit(() -> {
                try (var input = running.getOutputStream()) { input.write(payload); }
                return null;
            });
            Future<String> reader = workers.submit(() -> {
                try (var output = running.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = output.read(chunk)) != -1) {
                        if (bytes.size() + count > properties.getMaxOutputBytes()) {
                            throw failure(request, ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED, "技能沙箱输出超过上限");
                        }
                        bytes.write(chunk, 0, count);
                    }
                    return bytes.toString(StandardCharsets.UTF_8);
                }
            });
            // 先观察输出任务，超量输出立即触发清理，而非等待脚本继续运行到时间上限。
            String output = reader.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!running.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            int exit = running.exitValue();
            if (exit >= 125 && exit <= 127) throw failure(request, ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "技能沙箱或解释器不可用");
            if (exit != 0) throw failure(request, ApiErrorCode.SKILL_SANDBOX_FAILED, "技能脚本执行失败，请检查脚本逻辑");
            writer.get(remaining(deadline), TimeUnit.NANOSECONDS);
            return sanitizer.sanitize(output, List.of());
        } catch (TimeoutException failure) {
            throw failure(request, ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "技能沙箱执行超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure(request, ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "技能沙箱执行已中断");
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof SkillAccessException controlled) throw controlled;
            throw unavailable(request, failure);
        } catch (IOException failure) {
            throw unavailable(request, failure);
        } finally {
            // 清理必须先于线程池关闭，否则阻塞的 stdin/stdout 会让关闭一直等待。
            if (process != null) process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            boolean cleaned = !launched || cleanup(name);
            workers.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
            // 清理无法确认时保留占用名额，防止残留容器继续执行而新请求突破实例并发上限。
            if (cleaned) permits.release();
            if (!cleaned) throw failure(request, ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "技能沙箱清理失败，请联系管理员");
        }
    }

    /** 参数逐个传给 Docker，禁止 Shell 拼接；安全参数不允许由包或模型覆盖。 */
    List<String> command(String name) {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user=65534:65534", "--pids-limit=32", "--memory=128m", "--memory-swap=128m", "--cpus=0.5",
                "--ulimit=nofile=64:64", "--log-driver=none",
                "--tmpfs=/workspace:rw,noexec,nosuid,nodev,size=16m,mode=1777",
                "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777", "--workdir=/workspace", "-i"));
        if (!properties.getRuntime().isEmpty()) command.add("--runtime=" + properties.getRuntime());
        command.addAll(List.of("--entrypoint=python", properties.getImage(), "-I", "-B", "-u", "-c", BOOTSTRAP));
        return command;
    }

    /** 保留 Docker 客户端所需环境；不把 Server 的模型/JWT/数据库凭据继承给客户端进程。 */
    private static Process start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> original = Map.copyOf(builder.environment());
        builder.environment().clear();
        original.forEach((key, value) -> {
            if (List.of("PATH", "SYSTEMROOT", "WINDIR", "DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")
                    .contains(key.toUpperCase(java.util.Locale.ROOT))) builder.environment().put(key, value);
        });
        return builder.start();
    }

    /** 仅删除当前随机命名容器；自动删除后的“不存在”是正常结果，其他清理失败必须拒绝成功。 */
    private static boolean cleanup(String name) {
        Process remove = null;
        try {
            remove = start(List.of("docker", "rm", "-f", name));
            if (!remove.waitFor(5, TimeUnit.SECONDS)) { remove.destroyForcibly(); return false; }
            // docker rm 的错误输出很短且只含本次随机名称；不返回也不记录原文。
            String result = new String(remove.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            return remove.exitValue() == 0 || result.contains("No such container");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException failure) {
            return false;
        } finally {
            if (remove != null && remove.isAlive()) remove.destroyForcibly();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }

    static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 240 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.chars().anyMatch(Character::isISOControl)) return false;
        return java.util.Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    private static SkillAccessException unavailable(SkillReadRequest request, Exception cause) {
        SkillAccessException failure = failure(request, ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "技能沙箱不可用，请联系管理员");
        failure.initCause(cause);
        return failure;
    }

    private static SkillAccessException failure(SkillReadRequest request, ApiErrorCode code, String message) {
        return new SkillAccessException(code, message, request.attemptId().toString(), true);
    }
}
