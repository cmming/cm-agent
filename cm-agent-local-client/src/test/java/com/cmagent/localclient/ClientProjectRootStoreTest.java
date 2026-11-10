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

        UUID selectId = UUID.randomUUID();
        var select = executor.execute(identity, new LocalClientServerClient.Task(selectId,
                "PROJECT_SELECT_ROOT", 2,
                new ObjectMapper().createObjectNode().put("kind", "SELECT_ROOT")));

        assertThat(select.success()).isTrue();
        assertThat(select.output().toString()).doesNotContain(project.toString());
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
