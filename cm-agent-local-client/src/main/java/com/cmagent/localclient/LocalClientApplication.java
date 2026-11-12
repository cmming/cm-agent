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
 * {@code pair --server http://agent.example.com:8080 --name workstation} 生成一次性配对包；
 * 用户把输出 JSON 粘贴到已登录的控制台并确认后，进程保存设备标识。
 * {@code run} 以设备私钥签名的短轮询领取本机操作。客户端不自动提权，也不会在断线后重放已领取
 * 的外部操作。</p>
 */
public final class LocalClientApplication {
    private static final String FENCE_FILE = "client-project-fence.log";
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
            case "init-fence-store" -> initializeFenceStore(stateDirectory(parse(arguments)));
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
        char[] password = store.usesSystemStore() ? new char[0]
                : readPassword("设置本机身份口令：", true,
                    Boolean.parseBoolean(options.get("password-stdin")));
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
                        initializeFenceStore(store.stateDirectory());
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
        char[] password = store.usesSystemStore() ? new char[0]
                : readPassword("输入本机身份口令：", false,
                    Boolean.parseBoolean(options.get("password-stdin")));
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
                var handshake = new ClientProjectFenceHandshake(client,
                        ClientProjectRootStore.forCurrentUser(json),
                        new ClientProjectFenceJournal(store.stateDirectory().resolve(FENCE_FILE)));
                System.out.println("本机客户端已启动，设备标识：" + identity.metadata().deviceId());
                consumeTasks(identity, executor, client, running, handshake);
            }
            return 0;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 持有执行器期间持续领取任务；连接中断不销毁受管进程，也不重放不确定的领取或回执。
     * @param identity 当前已配对设备身份
     * @param executor 当前进程独占的操作执行器，由调用方关闭
     * @param client 签名通信客户端，由调用方关闭
     * @param running 客户端生命周期标志
     * @throws Exception 身份拒绝、中断或安全异常继续交给启动边界处理
     */
    static void consumeTasks(DeviceIdentityStore.StoredIdentity identity, LocalClientTaskExecutor executor,
            LocalClientServerClient client, AtomicBoolean running) throws Exception {
        consumeTasks(identity, executor, client, running, null);
    }

    /**
     * 持有执行器期间持续领取任务，并在每个运行级任务执行前核对持久代次。
     *
     * @param handshake 已初始化见证的服务端代次握手；为空只供旧测试装配
     */
    static void consumeTasks(DeviceIdentityStore.StoredIdentity identity, LocalClientTaskExecutor executor,
            LocalClientServerClient client, AtomicBoolean running, ClientProjectFenceHandshake handshake)
            throws Exception {
        boolean projectTurn = true;
        while (running.get()) {
            java.util.Optional<LocalClientServerClient.Task> task;
            try {
                // 两类任务使用独立端点；交替轮询避免同一最小时间片长期饿死另一队列。
                try {
                    task = projectTurn ? client.nextProjectTask(identity) : client.nextTask(identity);
                    projectTurn = !projectTurn;
                } catch (LocalClientServerClient.FenceRequiredException required) {
                    if (handshake == null) throw required;
                    try {
                        handshake.synchronize(identity, required.runId());
                    } catch (ClientProjectFenceJournal.MissingWitnessException missing) {
                        // 缺失见证无法通过继续轮询自愈；退出并提示显式初始化，避免空耗请求预算。
                        throw new LocalClientServerClient.ClientFailureException(
                                "设备项目运行代次见证缺失；请停止客户端并运行 init-fence-store 后再重启，"
                                        + "迁移前不会领取或执行项目任务", missing);
                    } catch (java.io.IOException unavailable) {
                        // 代次读取或确认响应可能丢失；本机见证已先落盘，重新核对后再继续。
                        System.err.println("设备运行代次握手暂不可用；稍后重新核对，不会执行任务");
                        Thread.sleep(Duration.ofSeconds(5));
                    }
                    continue;
                }
            } catch (LocalClientServerClient.ChallengeRateLimitedException limited) {
                // 每次空轮询包含挑战和签名请求；限流发生在领取之前，可延迟后重新申请挑战。
                System.err.println("设备请求过于频繁，稍后自动恢复连接");
                Thread.sleep(Duration.ofSeconds(5));
                continue;
            } catch (LocalClientServerClient.ClientFailureException rejected) {
                // 身份、协议或设备被拒绝不是可安全推断的网络故障，交给调用方处理。
                throw rejected;
            } catch (java.io.IOException unavailable) {
                // 领取响应可能丢失；不重发原请求或执行未收到的任务，仅延迟领取后续任务。
                System.err.println("设备连接暂不可用；原领取结果可能未知，稍后继续轮询");
                Thread.sleep(Duration.ofSeconds(5));
                continue;
            }
            if (task.isEmpty()) {
                // 留出每分钟的请求预算给任务结果回报，避免空闲轮询占满服务端额度。
                Thread.sleep(Duration.ofSeconds(2));
                continue;
            }
            var current = task.orElseThrow();
            current.requireValidFence();
            if (current.fencedProjectTask() && handshake == null) {
                throw new LocalClientServerClient.ClientFailureException(
                        "设备项目任务缺少本机代次见证，请使用已初始化状态目录重启客户端", null);
            }
            var outcome = current.fencedProjectTask()
                    ? handshake.executeTask(identity, current.runId(), current.rootId(), current.epoch(),
                            current.fencePhase(), current.category(), () -> executor.execute(identity, current))
                    : executor.execute(identity, current);
            if (current.category() != null && current.category().startsWith("PROJECT_")) {
                try {
                    client.reportProjectTask(identity, current, outcome.state(),
                            outcome.output(), outcome.error());
                } catch (LocalClientServerClient.ProjectResultRejectedException rejected) {
                    // 回执被拒绝后只继续处理新任务，绝不重放原操作或猜测服务端状态。
                    System.err.println(rejected.getMessage());
                } catch (LocalClientServerClient.ClientFailureException rejected) {
                    throw rejected;
                } catch (java.io.IOException unavailable) {
                    // 结果可能已送达；保留本机回执并继续轮询，不重发或再次执行原任务。
                    System.err.println("设备项目结果未确认，请核对原任务；不会自动重发");
                }
            } else {
                try {
                    client.reportTask(identity, current, outcome.state(),
                            outcome.output(), outcome.error());
                } catch (LocalClientServerClient.OperationResultRejectedException rejected) {
                    // 通用操作可能已被服务端收口为 UNKNOWN；拒绝迟到回执不能终止设备后续任务。
                    System.err.println(rejected.getMessage());
                } catch (LocalClientServerClient.ClientFailureException failure) {
                    if (!(failure.getCause() instanceof java.io.IOException)) throw failure;
                    // 通用操作的副作用也可能已经发生；只保留未知提示，不重发结果或执行。
                    System.err.println("本机操作结果未确认，请核对原任务；不会自动重发");
                }
            }
        }
    }

    private static int initializeFenceStore(Path directory) throws java.io.IOException {
        var journal = new ClientProjectFenceJournal(directory.resolve(FENCE_FILE));
        journal.initializeStore();
        System.out.println("设备项目运行代次见证已初始化");
        return 0;
    }

    private static int status() throws Exception {
        var store = DeviceIdentityStore.forCurrentUser();
        char[] password = store.usesSystemStore() ? new char[0]
                : readPassword("输入本机身份口令：", false, false);
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
        String backend = options.get("credential-backend");
        if (backend == null) backend = options.get("state-dir") == null ? "system" : "password";
        if (!("system".equals(backend) || "password".equals(backend))) {
            throw new IllegalArgumentException("--credential-backend 只能是 system 或 password");
        }
        Path directory = stateDirectory(options);
        if ("password".equals(backend)) {
            return new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), Optional.empty());
        }
        if (options.get("state-dir") == null) return DeviceIdentityStore.forCurrentUser();
        throw new IllegalArgumentException("自定义状态目录必须显式选择 password 凭据后端");
    }

    private static Path stateDirectory(java.util.Map<String, String> options) {
        String configured = options.get("state-dir");
        return configured == null
                ? Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize()
                    .resolve(".cm-agent").resolve("local-client")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static com.cmagent.core.domain.LocalClientDevice.Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS;
        if (os.contains("mac")) return com.cmagent.core.domain.LocalClientDevice.Platform.MACOS;
        if (os.contains("linux")) return com.cmagent.core.domain.LocalClientDevice.Platform.LINUX;
        throw new IllegalStateException("当前操作系统不受支持");
    }

    private static char[] readPassword(String prompt, boolean confirmationRequired,
            boolean standardInput) throws java.io.IOException {
        if (standardInput) return readPasswordFromStandardInput(confirmationRequired);
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

    /** 仅供受控自动化通过标准输入提供口令；参数、环境变量和日志均不承载秘密。 */
    private static char[] readPasswordFromStandardInput(boolean confirmationRequired) throws java.io.IOException {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                System.in, java.nio.charset.StandardCharsets.UTF_8));
        String first = reader.readLine();
        if (first == null || first.isEmpty()) throw new IllegalArgumentException("口令不能为空");
        if (!confirmationRequired) return first.toCharArray();
        String confirmation = reader.readLine();
        if (!first.equals(confirmation)) throw new IllegalArgumentException("两次口令不一致");
        return first.toCharArray();
    }

    private static java.util.Map<String, String> parse(String[] arguments) {
        java.util.Map<String, String> options = new java.util.HashMap<>();
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if ("--password-stdin".equals(argument)) {
                options.put("password-stdin", "true");
                continue;
            }
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
                  pair --server <URL> --name <设备名称> [--state-dir <目录>] [--credential-backend system|password] [--password-stdin]
                  run [--state-dir <目录>] [--credential-backend system|password] [--password-stdin]
                  init-fence-store [--state-dir <目录>]
                  status
                """);
    }
}
