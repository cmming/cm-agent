package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ClientProjectCommandOperationsTest {
    @Test void 固定工具链命令异步运行并可由停止链路取得退出证据(@TempDir Path directory)
            throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var binding = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        Path toolchainFile = toolchains(directory, mapper);
        var toolchains = new ClientProjectToolchains(toolchainFile, mapper);
        var journal = new ClientProjectCommandJournal(directory.resolve("commands"), mapper);
        var operations = new ClientProjectCommandOperations(roots, mapper, toolchains, journal);

        var planInput = mapper.createObjectNode().put("rootId", binding.rootId().toString())
                .put("toolchainId", "java-sleep").put("relativeDirectory", "")
                .put("timeoutSeconds", 20).put("maxOutputBytes", 65_536);
        planInput.putArray("arguments").add("20000");
        planInput.set("environment", mapper.createObjectNode());
        ObjectNode plan = operations.plan(identity, planInput);
        UUID commandId = UUID.randomUUID();
        ObjectNode startInput = mapper.createObjectNode()
                .put("rootId", binding.rootId().toString())
                .put("operationId", commandId.toString())
                .put("planDigest", plan.path("planDigest").asText());
        startInput.set("manifest", plan.path("manifest").deepCopy());

        ObjectNode running = operations.start(identity, startInput);
        assertThat(running.path("state").asText()).isEqualTo("RUNNING");

        ObjectNode stopInput = mapper.createObjectNode()
                .put("rootId", binding.rootId().toString())
                .put("operationId", commandId.toString());
        operations.stop(identity, stopInput);
        ObjectNode terminal = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            terminal = operations.status(identity, stopInput);
            if (!java.util.Set.of("RUNNING", "STOP_REQUESTED").contains(
                    terminal.path("state").asText())) break;
            Thread.sleep(50);
        }
        assertThat(terminal).isNotNull();
        assertThat(terminal.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(terminal.path("stopRequested").asBoolean()).isTrue();
        assertThat(journal.find(commandId).path("state").asText()).isEqualTo("CANCELLED");
        operations.close();
    }

    @Test void 停止链路清理已观察到的子进程后才报告取消(@TempDir Path directory)
            throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var binding = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        var operations = new ClientProjectCommandOperations(roots, mapper,
                new ClientProjectToolchains(toolchains(directory, mapper), mapper),
                new ClientProjectCommandJournal(directory.resolve("commands"), mapper));

        var planInput = mapper.createObjectNode().put("rootId", binding.rootId().toString())
                .put("toolchainId", "java-sleep").put("relativeDirectory", "")
                .put("timeoutSeconds", 20).put("maxOutputBytes", 65_536);
        planInput.putArray("arguments").add("child-process");
        planInput.set("environment", mapper.createObjectNode());
        ObjectNode plan = operations.plan(identity, planInput);
        UUID commandId = UUID.randomUUID();
        ObjectNode startInput = mapper.createObjectNode()
                .put("rootId", binding.rootId().toString())
                .put("operationId", commandId.toString())
                .put("planDigest", plan.path("planDigest").asText());
        startInput.set("manifest", plan.path("manifest").deepCopy());
        ObjectNode started = operations.start(identity, startInput);

        ObjectNode stopInput = mapper.createObjectNode()
                .put("rootId", binding.rootId().toString())
                .put("operationId", commandId.toString());
        long childPid = -1;
        for (int attempt = 0; attempt < 100 && childPid < 0; attempt++) {
            ObjectNode current = operations.status(identity, stopInput);
            childPid = childPid(current.path("stdoutBase64").asText(""));
            if (childPid < 0) Thread.sleep(50);
        }
        assertThat(childPid).isPositive();
        operations.stop(identity, stopInput);
        ObjectNode terminal = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            terminal = operations.status(identity, stopInput);
            if (!java.util.Set.of("RUNNING", "STOP_REQUESTED").contains(
                    terminal.path("state").asText())) break;
            Thread.sleep(50);
        }
        assertThat(terminal).isNotNull();
        assertThat(terminal.path("state").asText()).isEqualTo("CANCELLED");
        long expectedChild = childPid;
        for (int attempt = 0; attempt < 40; attempt++) {
            if (ProcessHandle.of(expectedChild).map(value -> !value.isAlive()).orElse(true)) break;
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        operations.close();
    }

    @Test void 项目构建配置在批准后变化时拒绝启动(@TempDir Path directory)
            throws Exception {
        var operations = operations(directory);
        var plan = operations.operations.plan(operations.identity,
                planInput(operations.binding, mapperArgs("0")));
        Files.writeString(operations.project.resolve("pom.xml"), "<project changed=\"true\"/>");
        ObjectNode start = new ObjectMapper().createObjectNode()
                .put("rootId", operations.binding.rootId().toString())
                .put("operationId", UUID.randomUUID().toString())
                .put("planDigest", plan.path("planDigest").asText());
        start.set("manifest", plan.path("manifest").deepCopy());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        operations.operations.start(operations.identity, start))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("构建配置");
        operations.operations.close();
    }

    @Test void 敏感命令参数在计划阶段拒绝且不会创建启动许可(@TempDir Path directory)
            throws Exception {
        var operations = operations(directory);
        ObjectNode input = planInput(operations.binding, mapperArgs("--password=fixture"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        operations.operations.plan(operations.identity, input))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("秘密或凭据");
        operations.operations.close();
    }

    private static Fixture operations(Path directory) throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var binding = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        var operations = new ClientProjectCommandOperations(roots, mapper,
                new ClientProjectToolchains(toolchains(directory, mapper), mapper),
                new ClientProjectCommandJournal(directory.resolve("commands"), mapper));
        return new Fixture(identity, binding, project, operations);
    }

    private static ObjectNode planInput(ClientProjectRootStore.RootBinding binding,
            com.fasterxml.jackson.databind.node.ArrayNode arguments) {
        var mapper = new ObjectMapper();
        var input = mapper.createObjectNode().put("rootId", binding.rootId().toString())
                .put("toolchainId", "java-sleep").put("relativeDirectory", "")
                .put("timeoutSeconds", 20).put("maxOutputBytes", 65_536);
        input.set("arguments", arguments);
        input.set("environment", mapper.createObjectNode());
        return input;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode mapperArgs(String... values) {
        var mapper = new ObjectMapper();
        var arguments = mapper.createArrayNode();
        for (String value : values) arguments.add(value);
        return arguments;
    }

    private record Fixture(DeviceIdentityStore.StoredIdentity identity,
            ClientProjectRootStore.RootBinding binding, Path project,
            ClientProjectCommandOperations operations) { }

    private static long childPid(String encoded) {
        if (encoded.isBlank()) return -1;
        String output = new String(java.util.Base64.getDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("CHILD_PID=(\\d+)").matcher(output);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    private static Path toolchains(Path directory, ObjectMapper mapper) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toAbsolutePath().toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().normalize()
                .toString();
        ObjectNode root = mapper.createObjectNode().put("version", 1);
        ObjectNode value = root.putArray("toolchains").addObject()
                .put("id", "java-sleep").put("displayName", "Java 合成休眠命令")
                .put("executable", executable).put("build", false);
        value.putArray("prefixArguments").add("-cp").add(classpath)
                .add(ClientProjectCommandSleepFixture.class.getName());
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : java.util.List.of("PATH", "SystemRoot", "TEMP", "TMP")) {
            if (System.getenv(name) != null) environment.put(name, System.getenv(name));
        }
        value.set("environment", mapper.valueToTree(environment));
        value.putArray("allowedEnvironment");
        Path file = directory.resolve("toolchains.json");
        mapper.writeValue(file.toFile(), root);
        return file;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }
}

final class ClientProjectCommandSleepFixture {
    private ClientProjectCommandSleepFixture() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "child-process".equals(arguments[0])) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                    isWindows() ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    ClientProjectCommandChildSleepFixture.class.getName()).start();
            System.out.println("CHILD_PID=" + child.pid());
            System.out.flush();
            Thread.sleep(20_000);
            return;
        }
        System.out.println("COMMAND_STARTED");
        System.out.flush();
        Thread.sleep(arguments.length == 0 ? 5_000 : Long.parseLong(arguments[0]));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }
}

final class ClientProjectCommandChildSleepFixture {
    private ClientProjectCommandChildSleepFixture() { }

    public static void main(String[] arguments) throws Exception {
        Thread.sleep(20_000);
    }
}
