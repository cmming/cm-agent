package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 当前 OS 用户显式配置的固定工具链。
 *
 * <p>文件默认位于 {@code ~/.cm-agent/client-toolchains.json}，也可由启动参数
 * {@code -Dcm.agent.client.toolchains.path=<绝对路径>} 指定。模型只能选择逻辑 id；
 * 绝对可执行文件路径、固定前缀参数和基础环境只在客户端读取，不进入 Server 或模型输出。</p>
 */
final class ClientProjectToolchains {
    private static final int VERSION = 1;
    private static final long MAX_CONFIG_BYTES = 1_048_576L;
    private static final int MAX_ENVIRONMENT_ENTRIES = 128;
    private static final int MAX_ENVIRONMENT_BYTES = 131_072;
    private static final Set<String> DENIED_EXECUTABLES = Set.of(
            "cmd.exe", "powershell.exe", "pwsh.exe", "wsl.exe", "sh", "bash", "zsh");
    private final Path file;
    private final ObjectMapper json;

    ClientProjectToolchains(Path file, ObjectMapper json) {
        if (file == null || !file.isAbsolute()) {
            throw new IllegalArgumentException("客户端工具链配置路径必须为绝对路径");
        }
        this.file = file.toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(json);
    }

    static ClientProjectToolchains forCurrentUser(ObjectMapper json) {
        String configured = System.getProperty("cm.agent.client.toolchains.path", "").trim();
        if (!configured.isEmpty()) return new ClientProjectToolchains(Path.of(configured), json);
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        return new ClientProjectToolchains(
                home.resolve(".cm-agent").resolve("client-toolchains.json"), json);
    }

    /** @return 只含逻辑 id、展示名和程序文件名的安全清单 */
    List<View> list() throws IOException {
        return load().values().stream().map(value -> new View(value.id(), value.displayName(),
                value.executable().getFileName().toString(), value.build())).toList();
    }

    /** @return 按逻辑 id 查找的受保护完整工具链 */
    Toolchain require(String id) throws IOException {
        Toolchain value = load().get(id);
        if (value == null) throw new IOException("客户端工具链未配置");
        return value;
    }

    private Map<String, Toolchain> load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("客户端工具链配置不是普通文件");
        }
        if (Files.size(file) > MAX_CONFIG_BYTES) {
            throw new IOException("客户端工具链配置超过预算");
        }
        Configuration configuration;
        try {
            configuration = json.readValue(file.toFile(), Configuration.class);
        } catch (RuntimeException | IOException invalid) {
            throw new IOException("客户端工具链配置损坏", invalid);
        }
        if (configuration == null || configuration.version() != VERSION
                || configuration.toolchains() == null || configuration.toolchains().size() > 32) {
            throw new IOException("客户端工具链配置版本或预算无效");
        }
        Map<String, Toolchain> validated = new LinkedHashMap<>();
        for (Toolchain value : configuration.toolchains()) {
            Toolchain safe = validate(value);
            if (validated.putIfAbsent(safe.id(), safe) != null) {
                throw new IOException("客户端工具链标识重复");
            }
        }
        return Map.copyOf(validated);
    }

    private Toolchain validate(Toolchain value) throws IOException {
        if (value == null || value.id() == null
                || !value.id().matches("[a-z][a-z0-9._-]{0,63}")
                || value.displayName() == null || value.displayName().isBlank()
                || value.displayName().length() > 120 || value.executable() == null
                || value.prefixArguments() == null || value.environment() == null
                || value.allowedEnvironment() == null) {
            throw new IOException("客户端工具链字段无效");
        }
        Path executable = value.executable();
        if (!executable.isAbsolute()) throw new IOException("客户端工具链程序必须使用绝对路径");
        executable = executable.toRealPath();
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(executable) || Files.size(executable) > 134_217_728L
                || DENIED_EXECUTABLES.contains(executable.getFileName().toString()
                        .toLowerCase(Locale.ROOT))) {
            throw new IOException("客户端工具链程序不安全或超过校验预算");
        }
        if (value.prefixArguments().size() > 32
                || value.prefixArguments().stream().anyMatch(item -> item == null
                    || item.indexOf('\0') >= 0 || item.length() > 32_768)) {
            throw new IOException("客户端工具链固定参数无效");
        }
        Map<String, String> environment = safeEnvironment(value.environment(), true);
        Set<String> allowed = new HashSet<>();
        for (String name : value.allowedEnvironment()) {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || sensitive(name)) {
                throw new IOException("客户端工具链可覆盖环境名无效");
            }
            allowed.add(name.toUpperCase(Locale.ROOT));
        }
        return new Toolchain(value.id(), value.displayName(), executable,
                List.copyOf(value.prefixArguments()), Map.copyOf(environment),
                Set.copyOf(allowed), value.build());
    }

    private static Map<String, String> safeEnvironment(Map<String, String> source,
            boolean internal) throws IOException {
        if (source.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IOException("客户端工具链环境字段过多");
        }
        Map<String, String> values = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || value == null || value.indexOf('\0') >= 0 || value.length() > 8192
                    || !internal && sensitive(key)) {
                throw new IOException("客户端工具链环境无效");
            }
            totalBytes += key.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_ENVIRONMENT_BYTES) {
                throw new IOException("客户端工具链环境超过预算");
            }
            values.put(key, value);
        }
        return values;
    }

    static Map<String, String> safeOverrides(Map<String, String> source, Set<String> allowed)
            throws IOException {
        Map<String, String> result = safeEnvironment(source, false);
        for (String key : result.keySet()) {
            if (!allowed.contains(key.toUpperCase(Locale.ROOT))) {
                throw new IOException("客户端工具链不允许覆盖该环境字段");
            }
        }
        return result;
    }

    static boolean sensitive(String key) {
        return key.toUpperCase(Locale.ROOT)
                .matches(".*(SECRET|PASSWORD|TOKEN|API_KEY|PRIVATE_KEY|CREDENTIAL).*");
    }

    static boolean sensitiveArgument(String value) {
        return value.matches("(?is).*(password|passwd|api[-_.]?key|secret|token|credential)"
                + "\\s*[:=].*")
                || value.matches("(?i)--?(password|passwd|api[-_.]?key|secret|token|credential)")
                || value.matches("(?i).*Bearer\\s+.+");
    }

    static String digest(ObjectMapper json, Object value) throws IOException {
        byte[] bytes = json.copy().configure(
                com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                true).writeValueAsBytes(value);
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** @param version 固定配置版本 @param toolchains 用户显式允许的工具链 */
    record Configuration(int version, List<Toolchain> toolchains) {
        Configuration {
            toolchains = toolchains == null ? List.of() : List.copyOf(toolchains);
        }
    }

    /**
     * @param id 模型可选择的逻辑标识
     * @param displayName 项目页展示名
     * @param executable 本机可执行文件绝对路径，不进入 Server
     * @param prefixArguments 固定启动参数，属于批准清单
     * @param environment 固定基础环境，不自动继承宿主环境
     * @param allowedEnvironment 用户可显式覆盖的环境名
     * @param build 是否明确标记为构建工具
     */
    record Toolchain(String id, String displayName, Path executable,
            List<String> prefixArguments, Map<String, String> environment,
            Set<String> allowedEnvironment, boolean build) {
        Toolchain {
            prefixArguments = prefixArguments == null ? List.of() : List.copyOf(prefixArguments);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            allowedEnvironment = allowedEnvironment == null ? Set.of()
                    : Set.copyOf(allowedEnvironment);
        }

        Toolchain sanitized(Path executable) throws IOException {
            return new Toolchain(id, displayName, executable, prefixArguments,
                    environment, allowedEnvironment, build);
        }

        @Override public String toString() {
            return "ClientToolchain[id=" + id + ",executable=<受保护>]";
        }
    }

    /** @param toolchainId 逻辑标识 @param displayName 展示名 @param executable 程序文件名 @param build 是否为构建工具 */
    record View(String toolchainId, String displayName, String executable, boolean build) { }
}
