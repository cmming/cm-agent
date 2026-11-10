package com.cmagent.localclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在用户确认的项目根内形成、启动和查询固定工具链命令。
 *
 * <p>计划阶段不启动程序；启动前重新核对工具链定义、可执行文件、主要构建配置和工作目录。
 * 根进程启动后以有界输出异步运行，停止请求必须继续轮询到退出或 UNKNOWN。本类不是沙箱，
 * 进程拥有当前客户端账号可访问的文件和网络权限。</p>
 */
final class ClientProjectCommandOperations implements AutoCloseable {
    static final int MAX_ARGUMENTS = 128;
    static final int MAX_OUTPUT_BYTES = 524_288;
    private static final long MAX_EXECUTABLE_BYTES = 134_217_728L;
    private static final long MAX_BUILD_FILE_BYTES = 2_097_152L;
    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "package.json", "package-lock.json", "gradle.lockfile",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "Makefile");
    private final ClientProjectRootStore roots;
    private final ObjectMapper json;
    private final ClientProjectToolchains toolchains;
    private final ClientProjectCommandJournal journal;
    private final Semaphore slot = new Semaphore(1);
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Thread> monitors = new ConcurrentHashMap<>();

    ClientProjectCommandOperations(ClientProjectRootStore roots, ObjectMapper json) {
        this(roots, json, ClientProjectToolchains.forCurrentUser(json),
                ClientProjectCommandJournal.forCurrentUser(json));
    }

    ClientProjectCommandOperations(ClientProjectRootStore roots, ObjectMapper json,
            ClientProjectToolchains toolchains, ClientProjectCommandJournal journal) {
        this.roots = roots;
        this.json = json;
        this.toolchains = toolchains;
        this.journal = journal;
    }

    ObjectNode list(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        roots.verify(identity, UUID.fromString(text(input, "rootId")));
        ObjectNode output = json.createObjectNode();
        ArrayNode items = output.putArray("toolchains");
        for (ClientProjectToolchains.View value : toolchains.list()) {
            items.addObject().put("toolchainId", value.toolchainId())
                    .put("displayName", value.displayName())
                    .put("executable", value.executable()).put("build", value.build());
        }
        return output;
    }

    ObjectNode plan(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        UUID rootId = UUID.fromString(text(input, "rootId"));
        var binding = roots.verify(identity, rootId);
        ClientProjectToolchains.Toolchain toolchain = toolchains.require(
                text(input, "toolchainId"));
        List<String> arguments = strings(input.path("arguments"), "命令参数", MAX_ARGUMENTS, true);
        if (arguments.stream().anyMatch(ClientProjectToolchains::sensitiveArgument)) {
            throw new IOException("命令参数不允许包含秘密或凭据赋值");
        }
        String relativeDirectory = optionalText(input, "relativeDirectory", 1024);
        Path directory = commandDirectory(binding, relativeDirectory);
        int timeoutSeconds = integer(input, "timeoutSeconds", 1, 1800);
        int maxOutputBytes = integer(input, "maxOutputBytes", 1, MAX_OUTPUT_BYTES);
        Map<String, String> overrides = objectMap(input.path("environment"), "环境");
        overrides = ClientProjectToolchains.safeOverrides(overrides,
                toolchain.allowedEnvironment());
        Map<String, String> environment = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        environment.putAll(toolchain.environment());
        environment.putAll(overrides);
        List<String> fullArguments = new ArrayList<>(toolchain.prefixArguments());
        fullArguments.addAll(arguments);
        if (fullArguments.size() > MAX_ARGUMENTS
                || fullArguments.stream().anyMatch(value -> value == null
                    || value.length() > 32_768 || value.indexOf('\0') >= 0)) {
            throw new IOException("固定工具链参数预算无效");
        }
        String executableHash = fileHash(toolchain.executable(), MAX_EXECUTABLE_BYTES);
        Map<String, String> buildHashes = buildHashes(binding);
        ObjectNode manifest = json.createObjectNode().put("version", 1)
                .put("rootId", binding.rootId().toString())
                .put("toolchainId", toolchain.id())
                .put("toolchainDigest", toolchainDigest(toolchain))
                .put("executableHash", executableHash)
                .put("relativeDirectory", relativeDirectory)
                .put("timeoutSeconds", timeoutSeconds)
                .put("maxOutputBytes", maxOutputBytes);
        manifest.set("prefixArguments", json.valueToTree(toolchain.prefixArguments()));
        manifest.set("arguments", json.valueToTree(arguments));
        manifest.set("environment", json.valueToTree(environment));
        manifest.set("buildHashes", json.valueToTree(buildHashes));
        String planDigest = ClientProjectToolchains.digest(json, manifest);

        ObjectNode preview = json.createObjectNode()
                .put("toolchainId", toolchain.id()).put("displayName", toolchain.displayName())
                .put("executable", toolchain.executable().getFileName().toString())
                .put("executableHash", executableHash).put("build", toolchain.build())
                .put("relativeDirectory", relativeDirectory)
                .put("timeoutSeconds", timeoutSeconds).put("maxOutputBytes", maxOutputBytes)
                .put("environmentDigest",
                        ClientProjectToolchains.digest(json, toolchain.environment()));
        preview.set("prefixArguments", json.valueToTree(toolchain.prefixArguments()));
        preview.set("arguments", json.valueToTree(arguments));
        preview.set("environmentOverrides", json.valueToTree(overrides));
        preview.set("environmentKeys", json.valueToTree(environment.keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList()));
        preview.set("buildFiles", json.valueToTree(
                buildHashes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getKey).toList()));
        String displayDigest = ClientProjectToolchains.digest(json, preview);

        ObjectNode output = json.createObjectNode().put("planDigest", planDigest)
                .put("displayDigest", displayDigest).put("displayExact", true);
        output.set("preview", preview);
        output.set("manifest", manifest);
        return output;
    }

    ObjectNode start(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        UUID commandId = UUID.fromString(text(input, "operationId"));
        UUID rootId = UUID.fromString(text(input, "rootId"));
        var binding = roots.verify(identity, rootId);
        JsonNode manifest = input.path("manifest");
        String planDigest = text(input, "planDigest");
        if (!manifest.isObject() || !ClientProjectToolchains.digest(json, manifest)
                .equals(planDigest) || !rootId.toString().equals(
                        manifest.path("rootId").asText())) {
            throw new IOException("命令清单摘要或根身份不一致");
        }
        ClientProjectToolchains.Toolchain toolchain = toolchains.require(
                text(manifest, "toolchainId"));
        if (!toolchainDigest(toolchain).equals(text(manifest, "toolchainDigest"))
                || !fileHash(toolchain.executable(), MAX_EXECUTABLE_BYTES)
                    .equals(text(manifest, "executableHash"))) {
            throw new IOException("固定工具链或可执行文件在批准后发生变化");
        }
        String relativeDirectory = optionalText(manifest, "relativeDirectory", 1024);
        Path directory = commandDirectory(binding, relativeDirectory);
        if (!buildHashes(binding).equals(map(manifest.path("buildHashes"), "构建配置摘要"))) {
            throw new IOException("项目构建配置在批准后发生变化");
        }
        List<String> prefix = strings(manifest.path("prefixArguments"), "固定参数", 32, true);
        List<String> arguments = strings(manifest.path("arguments"), "命令参数", MAX_ARGUMENTS, true);
        if (!prefix.equals(toolchain.prefixArguments())
                || arguments.stream().anyMatch(ClientProjectToolchains::sensitiveArgument)) {
            throw new IOException("命令固定参数在批准后发生变化");
        }
        List<String> fullArguments = new ArrayList<>();
        fullArguments.add(toolchain.executable().toString());
        fullArguments.addAll(prefix);
        fullArguments.addAll(arguments);
        Map<String, String> environment = map(manifest.path("environment"), "命令环境");
        int timeoutSeconds = integer(manifest, "timeoutSeconds", 1, 1800);
        int maxOutputBytes = integer(manifest, "maxOutputBytes", 1, MAX_OUTPUT_BYTES);
        if (!slot.tryAcquire()) throw new IOException("已有客户端项目命令运行或停止中");
        boolean started = false;
        LocalCommandOperations.CommandHandle handle = null;
        try {
            journal.running(commandId, binding.rootId(), planDigest);
            handle = new LocalCommandOperations().start(
                    fullArguments, directory, environment, maxOutputBytes);
            Active value = new Active(commandId, binding.rootId(), planDigest, binding.path(),
                    environment, timeoutSeconds, handle);
            active.put(commandId, value);
            Thread monitor = Thread.ofVirtual().name("cm-agent-client-project-command")
                    .unstarted(() -> monitor(value));
            monitors.put(commandId, monitor);
            monitor.start();
            started = true;
            return status(value);
        } catch (IOException | RuntimeException failure) {
            if (started) {
                throw new PossibleSideEffectException("命令已启动，但启动任务回报失败", failure);
            }
            if (!started && handle != null) {
                // 进程已经创建但监视线程尚未接管时也必须先停止，避免请求失败后遗留用户权限进程。
                try {
                    handle.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                JsonNode receipt = journal.find(commandId);
                if (!started && receipt != null
                        && "RUNNING".equals(receipt.path("state").asText())) {
                    journal.update(commandId, handle == null ? "FAILED" : "UNKNOWN",
                            null, handle != null);
                }
            } catch (IOException journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            if (handle != null) {
                throw new PossibleSideEffectException("命令进程可能已启动并已请求停止", failure);
            }
            if (failure instanceof IOException ioFailure) throw ioFailure;
            throw failure;
        } finally {
            if (!started) slot.release();
        }
    }

    ObjectNode status(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        UUID commandId = UUID.fromString(text(input, "operationId"));
        roots.verify(identity, UUID.fromString(text(input, "rootId")));
        Active value = active.get(commandId);
        if (value != null) return status(value);
        JsonNode receipt = journal.find(commandId);
        if (receipt == null) throw new IOException("客户端命令回执不存在");
        return receipt(receipt);
    }

    ObjectNode stop(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        UUID commandId = UUID.fromString(text(input, "operationId"));
        roots.verify(identity, UUID.fromString(text(input, "rootId")));
        Active value = active.get(commandId);
        if (value == null) return status(identity, input);
        try {
            synchronized (value) {
                if (!java.util.Set.of("RUNNING", "STOP_REQUESTED").contains(value.state)) {
                    return status(value);
                }
                value.stop.set(true);
                if (!value.handle.await(Duration.ZERO).exited()) {
                    journal.update(commandId, "STOP_REQUESTED", null, true);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("命令停止请求被中断", interrupted);
        }
        return status(value);
    }

    private void monitor(Active value) {
        long deadline = System.nanoTime() + Duration.ofSeconds(value.timeoutSeconds).toNanos();
        long stopDeadline = Long.MAX_VALUE;
        long treeDeadline = Long.MAX_VALUE;
        boolean forced = false;
        String state = "UNKNOWN";
        Integer exitCode = null;
        try {
            while (true) {
                LocalCommandOperations.CommandStatus observed;
                synchronized (value) {
                    observed = value.handle.await(Duration.ofMillis(250));
                    value.accept(observed);
                }
                if (observed.exited()) {
                    exitCode = observed.exitCode();
                    if (observed.treeExited()) {
                        state = forced ? "FAILED" : value.stop.get() ? "CANCELLED"
                                : exitCode == 0 ? "SUCCEEDED" : "FAILED";
                        break;
                    }
                    if (treeDeadline == Long.MAX_VALUE) {
                        // 根进程退出但已观察后代仍存活时不能宣告完成，先尽力清理该命令树。
                        value.handle.requestStop();
                        forced = true;
                        treeDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    }
                }
                if (value.stop.get() && stopDeadline == Long.MAX_VALUE) {
                    value.handle.requestStop();
                    stopDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                }
                if (System.nanoTime() >= deadline && !forced) {
                    value.handle.requestStop();
                    forced = true;
                    stopDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                }
                if ((treeDeadline != Long.MAX_VALUE && System.nanoTime() >= treeDeadline)
                        || ((value.stop.get() || forced)
                            && System.nanoTime() >= stopDeadline)) {
                    state = "UNKNOWN";
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state = "UNKNOWN";
        } catch (RuntimeException failure) {
            state = "UNKNOWN";
        } finally {
            synchronized (value) {
                value.exitCode = exitCode;
                value.state = state;
            }
            try {
                value.handle.close();
            } catch (RuntimeException ignored) {
                value.state = "UNKNOWN";
            }
            try {
                journal.update(value.commandId, value.state, value.exitCode, value.stop.get());
            } catch (IOException ignored) {
                value.state = "UNKNOWN";
            }
            monitors.remove(value.commandId);
            slot.release();
        }
    }

    private ObjectNode status(Active value) throws IOException {
        try {
            synchronized (value) {
                LocalCommandOperations.CommandStatus observed = value.handle.await(Duration.ZERO);
                value.accept(observed);
                if (observed.exited()) {
                    value.exitCode = observed.exitCode();
                    if (observed.treeExited() && ("RUNNING".equals(value.state)
                            || "STOP_REQUESTED".equals(value.state))) {
                        String terminal = value.stop.get() ? "CANCELLED"
                                : observed.exitCode() == 0 ? "SUCCEEDED" : "FAILED";
                        journal.update(value.commandId, terminal, value.exitCode,
                                value.stop.get());
                        value.state = terminal;
                    }
                }
                return json.createObjectNode()
                        .put("operationId", commandId(value.commandId))
                        .put("state", value.state).put("sequence", value.sequence)
                        .put("exitCode", value.exitCode == null ? null : value.exitCode)
                        .put("truncated", value.truncated)
                        .put("outputAvailable", value.output.size() > 0)
                        .put("stopRequested", value.stop.get())
                        .put("stdoutBase64", Base64.getEncoder().encodeToString(
                                value.safeOutput().getBytes(StandardCharsets.UTF_8)))
                        .put("stderrBase64", "");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("命令状态等待被中断", interrupted);
        }
    }

    private ObjectNode receipt(JsonNode value) {
        return json.createObjectNode()
                .put("operationId", value.path("operationId").asText())
                .put("state", value.path("state").asText()).put("sequence", 0)
                .put("exitCode", value.path("exitCode").isInt()
                        ? value.path("exitCode").intValue() : null)
                .put("truncated", false).put("outputAvailable", false)
                .put("stopRequested", value.path("stopRequested").asBoolean(false))
                .put("stdoutBase64", "").put("stderrBase64", "");
    }

    private static String commandId(UUID id) {
        return id.toString();
    }

    private Path commandDirectory(ClientProjectRootStore.RootBinding binding, String relative)
            throws IOException {
        Path directory = roots.resolve(binding, relative);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("命令工作目录必须是项目根内的普通目录");
        }
        return directory;
    }

    private Map<String, String> buildHashes(ClientProjectRootStore.RootBinding binding)
            throws IOException {
        Map<String, String> hashes = new java.util.TreeMap<>();
        for (String relative : BUILD_FILES) {
            Path file = roots.resolve(binding, relative);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                throw new IOException("项目构建配置类型不安全");
            }
            hashes.put(relative, fileHash(file, MAX_BUILD_FILE_BYTES));
        }
        return Map.copyOf(hashes);
    }

    private String toolchainDigest(ClientProjectToolchains.Toolchain value) throws IOException {
        ObjectNode canonical = json.createObjectNode().put("id", value.id())
                .put("displayName", value.displayName())
                .put("executable", value.executable().toString())
                .put("build", value.build());
        canonical.set("prefixArguments", json.valueToTree(value.prefixArguments()));
        canonical.set("environment", json.valueToTree(value.environment()));
        canonical.set("allowedEnvironment", json.valueToTree(
                value.allowedEnvironment().stream().sorted().toList()));
        return ClientProjectToolchains.digest(json, canonical);
    }

    private static String fileHash(Path file, long maximum) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file) > maximum) {
            throw new IOException("待校验文件不安全或超过预算");
        }
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private List<String> strings(JsonNode value, String name, int maximum, boolean empty)
            throws IOException {
        if (value == null || !value.isArray() || value.size() > maximum || !empty
                && value.isEmpty()) {
            throw new IOException(name + "数量无效");
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().length() > 32_768
                    || item.textValue().indexOf('\0') >= 0) {
                throw new IllegalArgumentException(name + "字段无效");
            }
            result.add(item.textValue());
        });
        return List.copyOf(result);
    }

    private Map<String, String> objectMap(JsonNode value, String name) throws IOException {
        if (!value.isObject()) throw new IOException(name + "必须是对象");
        Map<String, String> result = new LinkedHashMap<>();
        var fields = value.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isTextual()) throw new IOException(name + "值无效");
            result.put(entry.getKey(), entry.getValue().textValue());
        }
        return result;
    }

    private Map<String, String> map(JsonNode value, String name) throws IOException {
        return objectMap(value, name);
    }

    private static int integer(JsonNode input, String field, int minimum, int maximum)
            throws IOException {
        JsonNode value = input.path(field);
        if (!value.isInt() || value.intValue() < minimum || value.intValue() > maximum) {
            throw new IOException(field + "预算无效");
        }
        return value.intValue();
    }

    private static String text(JsonNode input, String field) throws IOException {
        JsonNode value = input.path(field);
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > 1024 || value.textValue().indexOf('\0') >= 0) {
            throw new IOException(field + "字段无效");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode input, String field, int maximum)
            throws IOException {
        JsonNode value = input.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > maximum
                || value.textValue().indexOf('\0') >= 0) {
            throw new IOException(field + "字段无效");
        }
        return value.textValue();
    }

    @Override public void close() {
        for (Active value : active.values()) value.stop.set(true);
        for (Thread monitor : monitors.values()) {
            try {
                monitor.join(Duration.ofSeconds(7));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 进程或监视线程已经创建后失败，服务端必须保留 UNKNOWN 而不是按“未启动”处理。 */
    static final class PossibleSideEffectException extends IOException {
        PossibleSideEffectException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Active {
        private final UUID commandId;
        private final UUID rootId;
        private final String planDigest;
        private final String root;
        private final Map<String, String> environment;
        private final int timeoutSeconds;
        private final LocalCommandOperations.CommandHandle handle;
        private final AtomicBoolean stop = new AtomicBoolean();
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private String state = "RUNNING";
        private Integer exitCode;
        private long sequence;
        private boolean truncated;

        private Active(UUID commandId, UUID rootId, String planDigest, String root,
                Map<String, String> environment, int timeoutSeconds,
                LocalCommandOperations.CommandHandle handle) {
            this.commandId = commandId;
            this.rootId = rootId;
            this.planDigest = planDigest;
            this.root = root;
            this.environment = Map.copyOf(environment);
            this.timeoutSeconds = timeoutSeconds;
            this.handle = handle;
        }

        private synchronized void accept(LocalCommandOperations.CommandStatus value) {
            byte[] bytes = value.output();
            // await 返回从启动至今的累计输出；监视线程与状态查询共用快照，只追加尚未接收的尾部。
            int offset = Math.min(output.size(), bytes.length);
            int remaining = MAX_OUTPUT_BYTES - output.size();
            int accepted = Math.min(remaining, bytes.length - offset);
            output.write(bytes, offset, accepted);
            boolean nextTruncated = truncated || value.truncated() || accepted < bytes.length - offset;
            if (accepted > 0 || nextTruncated != truncated) sequence++;
            truncated = nextTruncated;
            java.util.Arrays.fill(bytes, (byte) 0);
        }

        private synchronized String safeOutput() {
            String value = output.toString(StandardCharsets.UTF_8)
                    .replace(root, "<项目目录>")
                    .replaceAll("[A-Za-z]:[\\\\/][^\\s\\\"<>]+", "<本机路径>")
                    .replaceAll("\\u001b\\[[0-?]*[ -/]*[@-~]", "")
                    .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                if (ClientProjectToolchains.sensitive(entry.getKey())
                        || entry.getValue().length() >= 8) {
                    value = value.replace(entry.getValue(), "<受控环境值>");
                }
            }
            return value;
        }

    }
}
