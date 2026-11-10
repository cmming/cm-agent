package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectRootStoreTest {
    @Test
    void 选择器只返回不透明根材料并可完成有界目录和文本读取(@TempDir Path directory)
            throws Exception {
        var identityStore = identityStore(directory.resolve("identity"));
        var identity = confirmedIdentity(identityStore);
        var project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("README.md"), "本地项目正文");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var executor = new LocalClientTaskExecutor(new ObjectMapper(), roots, ignored -> Optional.of(project));

        UUID taskId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        var select = executor.execute(identity, new LocalClientServerClient.Task(taskId, operationId,
                "PROJECT_SELECT_ROOT", 2,
                new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                        .put("expiresAt", java.time.Instant.now().plusSeconds(60).toString())));

        assertThat(select.success()).isTrue();
        assertThat(select.output().toString()).doesNotContain(project.toString());
        assertThat(roots.register(identity, taskId, project).displayName())
                .isEqualTo(select.output().path("displayName").asText());
        UUID rootId = UUID.fromString(select.output().path("rootId").asText());
        String rootIdentity = select.output().path("rootIdentity").asText();
        assertThat(rootIdentity).matches("[0-9a-f]{64}");

        var listInput = new ObjectMapper().createObjectNode().put("kind", "LIST")
                .put("rootId", rootId.toString()).put("path", "").putNull("cursor").put("limit", 50);
        var list = executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(),
                "PROJECT_READ_ONLY", 2, listInput));
        assertThat(list.success()).isTrue();
        assertThat(list.output().path("items").get(0).path("path").asText())
                .isEqualTo("README.md");

        var readInput = new ObjectMapper().createObjectNode().put("kind", "READ")
                .put("rootId", rootId.toString()).put("path", "README.md")
                .put("offset", 0).put("maxBytes", 65_536);
        var read = executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(),
                "PROJECT_READ_ONLY", 2, readInput));
        assertThat(read.success()).isTrue();
        assertThat(read.output().path("content").asText()).contains("本地项目正文");
        assertThat(read.output().path("sha256").asText()).matches("[0-9a-f]{64}");
    }

    @Test void 过期或缺少期限的选择任务不显示窗口也不登记(@TempDir Path directory) throws Exception {
        var identity = confirmedIdentity(identityStore(directory.resolve("identity")));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = new LocalClientTaskExecutor(new ObjectMapper(), roots, request -> {
            called.set(true);
            return Optional.of(directory);
        });
        var expired = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                .put("expiresAt", java.time.Instant.now().minusSeconds(1).toString());
        var missing = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT");
        assertThat(executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(), null,
                "PROJECT_SELECT_ROOT", 2, expired)).success()).isFalse();
        assertThat(executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(), null,
                "PROJECT_SELECT_ROOT", 2, missing)).success()).isFalse();
        assertThat(called).isFalse();
    }

    @Test void 确认窗口只显示规范服务端来源及无控制字符的设备名(@TempDir Path directory) throws Exception {
        var store = identityStore(directory.resolve("identity"));
        var identity = store.create(URI.create("https://user:fixture-only@example.test"), "测试\n设备",
                LocalClientDevice.Platform.WINDOWS, "fixture-only".toCharArray());
        identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
        var seen = new java.util.concurrent.atomic.AtomicReference<ClientDirectoryPicker.Request>();
        var executor = new LocalClientTaskExecutor(new ObjectMapper(),
                new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper()), request -> {
                    seen.set(request);
                    return Optional.empty();
                });
        UUID taskId = UUID.randomUUID();
        var request = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                .put("expiresAt", java.time.Instant.now().plusSeconds(30).toString());
        executor.execute(identity, new LocalClientServerClient.Task(taskId, null, "PROJECT_SELECT_ROOT", 2, request));
        assertThat(seen.get().serverOrigin()).isEqualTo("https://example.test");
        assertThat(seen.get().deviceName()).isEqualTo("测试?设备");
        assertThat(seen.get().taskId()).isEqualTo(taskId);
    }

    @Test void 选择异常脱敏且前端与日志使用同一编号(@TempDir Path directory) throws Exception {
        var identity = confirmedIdentity(identityStore(directory.resolve("identity")));
        var executor = new LocalClientTaskExecutor(new ObjectMapper(),
                new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper()), request -> {
                    throw new IllegalStateException("password=private https://internal.invalid C:\\secret");
                });
        var request = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                .put("expiresAt", java.time.Instant.now().plusSeconds(30).toString());
        var original = System.err;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var captured = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(captured);
            var outcome = executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(), null,
                    "PROJECT_SELECT_ROOT", 2, request));
            String logs = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            var id = java.util.regex.Pattern.compile("errorId=([0-9a-f-]{36})").matcher(logs);
            assertThat(id.find()).isTrue();
            assertThat(outcome.error()).contains("CLIENT_PROJECT_SELECTION_FAILED", id.group(1));
            assertThat(logs).contains("ERROR", "client_project_select", "CLIENT_PROJECT_SELECTION_FAILED");
            assertThat(logs).doesNotContain("password=private", "https://internal.invalid", "C:\\secret");
            assertThat(outcome.error()).doesNotContain("private", "internal.invalid", "C:\\secret");
        } finally {
            System.setErr(original);
        }
    }

    @Test void 目录重叠提示使用原项目且编号可在日志检索(@TempDir Path directory) throws Exception {
        var identity = confirmedIdentity(identityStore(directory.resolve("identity")));
        var project = Files.createDirectory(directory.resolve("project"));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        roots.register(identity, UUID.randomUUID(), project);
        var executor = new LocalClientTaskExecutor(new ObjectMapper(), roots, request -> Optional.of(project));
        var request = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                .put("expiresAt", java.time.Instant.now().plusSeconds(30).toString());
        var original = System.err;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var captured = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(captured);
            var outcome = executor.execute(identity, new LocalClientServerClient.Task(UUID.randomUUID(), null,
                    "PROJECT_SELECT_ROOT", 2, request));
            String logs = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            var id = java.util.regex.Pattern.compile("errorId=([0-9a-f-]{36})").matcher(logs);
            assertThat(id.find()).isTrue();
            assertThat(outcome.error()).contains("CLIENT_PROJECT_ROOT_CONFLICT", "使用原项目", id.group(1));
            assertThat(logs).contains("WARN", "CLIENT_PROJECT_ROOT_CONFLICT").doesNotContain(project.toString());
        } finally {
            System.setErr(original);
        }
    }

    @Test void 窗口返回时已过期不登记根目录(@TempDir Path directory) throws Exception {
        var identity = confirmedIdentity(identityStore(directory.resolve("identity")));
        var project = Files.createDirectory(directory.resolve("project"));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var executor = new LocalClientTaskExecutor(new ObjectMapper(), roots, request -> {
            while (request.expiresAt().isAfter(java.time.Instant.now())) Thread.onSpinWait();
            return Optional.of(project);
        });
        UUID taskId = UUID.randomUUID();
        var request = new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")
                .put("expiresAt", java.time.Instant.now().plusMillis(200).toString());
        assertThat(executor.execute(identity, new LocalClientServerClient.Task(taskId, null,
                "PROJECT_SELECT_ROOT", 2, request)).success()).isFalse();
        // 若迟到结果曾登记，同一 taskId 不能再绑定另一个目录。
        var other = Files.createDirectory(directory.resolve("other"));
        assertThat(roots.register(identity, taskId, other).displayName()).isEqualTo("other");
    }

    @Test
    void 同根别名和父子目录不能重复登记(@TempDir Path directory) throws Exception {
        var identityStore = identityStore(directory.resolve("identity"));
        var identity = confirmedIdentity(identityStore);
        var parent = Files.createDirectory(directory.resolve("workspace"));
        var child = Files.createDirectory(parent.resolve("module"));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());

        roots.register(identity, UUID.randomUUID(), child);

        assertThatThrownBy(() -> roots.register(identity, UUID.randomUUID(), parent))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("相同或互相包含");
        assertThatThrownBy(() -> roots.register(identity, UUID.randomUUID(),
                child.resolve(".")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("相同或互相包含");
    }

    @Test void 同一选择任务不能通过重放绑定不同目录(@TempDir Path directory) throws Exception {
        var identityStore = identityStore(directory.resolve("identity"));
        var identity = confirmedIdentity(identityStore);
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        UUID registrationId = UUID.randomUUID();

        roots.register(identity, registrationId, first);

        assertThatThrownBy(() -> roots.register(identity, registrationId, second))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("不同目录");
    }

    private static DeviceIdentityStore identityStore(Path directory) {
        return new DeviceIdentityStore(directory.toAbsolutePath(), new ObjectMapper(),
                new SecureRandom(), Optional.empty());
    }

    private static DeviceIdentityStore.StoredIdentity confirmedIdentity(
            DeviceIdentityStore store) throws Exception {
        var identity = store.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        return store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
    }
}
