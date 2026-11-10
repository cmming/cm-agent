package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Console;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CM Agent 本机客户端命令行入口。
 *
 * <p>示例：
 * {@code pair --server https://agent.example.com --name workstation} 生成一次性配对包；
 * 用户把输出 JSON 粘贴到已登录的控制台并确认后，进程保存设备标识。
 * {@code run} 以设备私钥签名的短轮询领取本机操作。客户端不自动提权，也不会在断线后重放已领取
 * 的外部操作。</p>
 */
public final class LocalClientApplication {
    private LocalClientApplication() { }

    /** @param arguments 固定子命令和参数 */
    public static void main(String[] arguments) {
        try {
            int exit = run(arguments);
            if (exit != 0) System.exit(exit);
        } catch (LocalClientServerClient.ClientFailureException unavailable) {
            // 仅输出客户端生成的固定提示，不回显外部响应或设备凭据。
            System.err.println(unavailable.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException invalid) {
            System.err.println("参数错误：" + invalid.getMessage());
            System.exit(2);
        } catch (Exception failure) {
            System.err.println("本机客户端操作失败：" + safeReason(failure));
            System.exit(1);
        }
    }

    static int run(String[] arguments) throws Exception {
        if (arguments == null || arguments.length == 0) {
            usage();
            return 2;
        }
        return switch (arguments[0]) {
            case "pair" -> pair(parse(arguments));
            case "run" -> runLoop(parse(arguments));
            case "status" -> status();
            case "help", "--help", "-h" -> {
                usage();
                yield 0;
            }
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static int pair(java.util.Map<String, String> options) throws Exception {
        URI server = URI.create(required(options, "server"));
        String name = required(options, "name");
        var platform = currentPlatform();
        var store = store(options);
        char[] password = store.usesSystemStore() ? new char[0] : readPassword("设置本机身份口令：", true);
        try {
            var identity = store.create(server, name, platform, password);
            var json = new ObjectMapper();
            try (var client = new LocalClientServerClient(json, new SecureRandom())) {
                var pairing = client.pairingPackage(identity);
                System.out.println("把下面 JSON 粘贴到 CM Agent 控制台的“设备配对”页面并确认：");
                System.out.println(json.writeValueAsString(pairing));
                // 服务端尚未收到配对包时不会创建会话，限制本机等待时间以免永久轮询。
                long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                while (System.nanoTime() - deadline < 0) {
                    var status = client.pairingStatus(server, pairing.requestId()).orElse(null);
                    if (status == null) {
                        Thread.sleep(Duration.ofSeconds(2));
                        continue;
                    }
                    if ("CONFIRMED".equals(status.state())) {
                        store.confirm(identity, status.deviceId(), status.tenantId(), status.ownerId());
                        System.out.println("配对完成，设备标识：" + status.deviceId());
                        return 0;
                    }
                    if ("EXPIRED".equals(status.state())) {
                        System.err.println("配对已过期，请使用新的状态目录重新运行 pair");
                        return 1;
                    }
                    Thread.sleep(Duration.ofSeconds(2));
                }
                System.err.println("等待配对超时，请检查服务端功能是否启用，并使用新的状态目录重新运行 pair");
                return 1;
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static int runLoop(java.util.Map<String, String> options) throws Exception {
        var store = store(options);
        char[] password = store.usesSystemStore() ? new char[0] : readPassword("输入本机身份口令：", false);
        try {
            var identity = store.load(password);
            if (identity.metadata().deviceId() == null) {
                throw new IllegalStateException("设备尚未完成浏览器配对");
            }
            var json = new ObjectMapper();
            AtomicBoolean running = new AtomicBoolean(true);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false), "cm-agent-local-client-shutdown"));
            try (var executor = new LocalClientTaskExecutor(json);
                 var client = new LocalClientServerClient(json, new SecureRandom())) {
                System.out.println("本机客户端已启动，设备标识：" + identity.metadata().deviceId());
                boolean projectTurn = true;
                while (running.get()) {
                    java.util.Optional<LocalClientServerClient.Task> task;
                    try {
                        // 两类任务使用独立端点；交替轮询避免同一最小时间片长期饿死另一队列。
                        task = projectTurn ? client.nextProjectTask(identity) : client.nextTask(identity);
                        projectTurn = !projectTurn;
                    } catch (LocalClientServerClient.ChallengeRateLimitedException limited) {
                        // 每次空轮询包含挑战和签名请求；限流发生在领取之前，可延迟后重新申请挑战。
                        System.err.println("设备请求过于频繁，稍后自动恢复连接");
                        Thread.sleep(Duration.ofSeconds(5));
                        continue;
                    }
                    if (task.isEmpty()) {
                        // 留出每分钟的请求预算给任务结果回报，避免空闲轮询占满服务端额度。
                        Thread.sleep(Duration.ofSeconds(2));
                        continue;
                    }
                    var current = task.orElseThrow();
                    var outcome = executor.execute(identity, current);
                    if (current.category() != null && current.category().startsWith("PROJECT_")) {
                        client.reportProjectTask(identity, current, outcome.state(),
                                outcome.output(), outcome.error());
                    } else {
                        client.reportTask(identity, current, outcome.state(),
                                outcome.output(), outcome.error());
                    }
                }
            }
            return 0;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static int status() throws Exception {
        var store = DeviceIdentityStore.forCurrentUser();
        char[] password = store.usesSystemStore() ? new char[0] : readPassword("输入本机身份口令：", false);
        try {
            var identity = store.load(password);
            var metadata = identity.metadata();
            System.out.println("服务端：" + metadata.serverUrl());
            System.out.println("设备名称：" + metadata.deviceName());
            System.out.println("设备标识：" + (metadata.deviceId() == null ? "未配对" : metadata.deviceId()));
            System.out.println("凭据存储：" + metadata.storageKind());
            return 0;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static DeviceIdentityStore store(java.util.Map<String, String> options) {
        String configured = options.get("state-dir");
        if (configured == null) return DeviceIdentityStore.forCurrentUser();
        return new DeviceIdentityStore(Path.of(configured).toAbsolutePath().normalize(),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
    }

    private static com.cmagent.core.domain.LocalClientDevice.Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS;
        if (os.contains("mac")) return com.cmagent.core.domain.LocalClientDevice.Platform.MACOS;
        if (os.contains("linux")) return com.cmagent.core.domain.LocalClientDevice.Platform.LINUX;
        throw new IllegalStateException("当前操作系统不受支持");
    }

    private static char[] readPassword(String prompt, boolean confirmationRequired) {
        Console console = System.console();
        if (console == null) throw new IllegalStateException("当前终端不能安全读取口令");
        char[] first = console.readPassword(prompt);
        if (first == null || first.length == 0) throw new IllegalArgumentException("口令不能为空");
        if (!confirmationRequired) return first;
        char[] confirmation = console.readPassword("再次输入口令：");
        try {
            if (!Arrays.equals(first, confirmation)) throw new IllegalArgumentException("两次口令不一致");
            return first.clone();
        } finally {
            Arrays.fill(first, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private static java.util.Map<String, String> parse(String[] arguments) {
        java.util.Map<String, String> options = new java.util.HashMap<>();
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("参数格式无效");
            }
            options.put(argument.substring(2), arguments[++index]);
        }
        return options;
    }

    private static String required(java.util.Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --" + name);
        return value;
    }

    private static String safeReason(Exception failure) {
        if (failure instanceof IllegalArgumentException) return failure.getMessage();
        return failure.getClass().getSimpleName();
    }

    private static void usage() {
        System.out.println("""
                CM Agent 本机客户端
                  pair --server <URL> --name <设备名称> [--state-dir <目录>]
                  run [--state-dir <目录>]
                  status
                """);
    }
}
