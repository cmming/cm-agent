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

class ClientProjectChangeOperationsTest {
    @Test
    void 计划校正原文后条件应用并在批准后拒绝替换竞态(@TempDir Path directory)
            throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Path readme = project.resolve("README.md");
        Files.writeString(readme, "原内容");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var root = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        var operations = new ClientProjectChangeOperations(roots, mapper,
                new ClientProjectOperationJournal(directory.resolve("operations"), mapper));

        var request = mapper.createObjectNode().put("rootId", root.rootId().toString());
        request.putArray("changes").addObject().put("kind", "REPLACE_FILE")
                .put("path", "README.md").put("content", "新内容")
                .put("expectedSha256", sha256("原内容".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var plan = operations.plan(identity, request);

        var apply = mapper.createObjectNode().put("rootId", root.rootId().toString())
                .put("operationId", UUID.randomUUID().toString())
                .put("planDigest", plan.path("planDigest").asText());
        apply.set("changes", plan.path("changes").deepCopy());
        var result = operations.apply(identity, apply);

        assertThat(Files.readString(readme)).isEqualTo("新内容");
        assertThat(result.path("planDigest").asText())
                .isEqualTo(plan.path("planDigest").asText());

        var stale = mapper.createObjectNode().put("rootId", root.rootId().toString())
                .put("operationId", UUID.randomUUID().toString())
                .put("planDigest", plan.path("planDigest").asText());
        stale.set("changes", plan.path("changes").deepCopy());
        Files.writeString(readme, "外部修改");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                operations.apply(identity, stale)))
                .hasMessageContaining("批准后发生变化");
        assertThat(Files.readString(readme)).isEqualTo("外部修改");
    }

    @Test void 移动文件及空目录动作保持条件身份(@TempDir Path directory) throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Path source = Files.writeString(project.resolve("source.txt"), "正文");
        Files.createDirectory(project.resolve("empty"));
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var root = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        var operations = new ClientProjectChangeOperations(roots, mapper,
                new ClientProjectOperationJournal(directory.resolve("operations"), mapper));
        var request = mapper.createObjectNode().put("rootId", root.rootId().toString());
        var changes = request.putArray("changes");
        changes.addObject().put("kind", "CREATE_DIRECTORY").put("path", "created");
        changes.addObject().put("kind", "MOVE_FILE").put("path", "source.txt")
                .put("target", "created/source.txt");
        changes.addObject().put("kind", "DELETE_EMPTY_DIRECTORY").put("path", "empty");
        var plan = operations.plan(identity, request);
        var apply = mapper.createObjectNode().put("rootId", root.rootId().toString())
                .put("operationId", UUID.randomUUID().toString())
                .put("planDigest", plan.path("planDigest").asText());
        apply.set("changes", plan.path("changes").deepCopy());

        operations.apply(identity, apply);

        assertThat(source).doesNotExist();
        assertThat(project.resolve("created/source.txt")).hasContent("正文");
        assertThat(project.resolve("empty")).doesNotExist();
    }

    @Test void 同批重复目标或普通文件父子路径在计划阶段拒绝(@TempDir Path directory)
            throws Exception {
        var identityStore = new DeviceIdentityStore(directory.resolve("identity"),
                new ObjectMapper(), new SecureRandom(), Optional.empty());
        var created = identityStore.create(URI.create("http://127.0.0.1:8080"), "测试电脑",
                LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
        var identity = identityStore.confirm(created, UUID.randomUUID(), UUID.randomUUID(),
                "owner");
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("first.txt"), "一");
        Files.writeString(project.resolve("second.txt"), "二");
        var roots = new ClientProjectRootStore(directory.resolve("roots"), new ObjectMapper());
        var root = roots.register(identity, UUID.randomUUID(), project);
        var mapper = new ObjectMapper();
        var operations = new ClientProjectChangeOperations(roots, mapper,
                new ClientProjectOperationJournal(directory.resolve("operations"), mapper));

        var duplicateTarget = mapper.createObjectNode().put("rootId", root.rootId().toString());
        var duplicateChanges = duplicateTarget.putArray("changes");
        duplicateChanges.addObject().put("kind", "MOVE_FILE")
                .put("path", "first.txt").put("target", "target-a")
                .put("expectedSha256", sha256("一".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        duplicateChanges.addObject().put("kind", "MOVE_FILE")
                .put("path", "second.txt").put("target", "TARGET-A")
                .put("expectedSha256", sha256("二".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                operations.plan(identity, duplicateTarget)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("目标重复");

        var parentFile = mapper.createObjectNode().put("rootId", root.rootId().toString());
        var parentChanges = parentFile.putArray("changes");
        parentChanges.addObject().put("kind", "CREATE_FILE")
                .put("path", "parent").put("content", "文件");
        parentChanges.addObject().put("kind", "CREATE_FILE")
                .put("path", "parent/child").put("content", "子文件");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                operations.plan(identity, parentFile)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("父子重叠");
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }
}
