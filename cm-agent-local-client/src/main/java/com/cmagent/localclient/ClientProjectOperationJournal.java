package com.cmagent.localclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * 客户端私有文件变更回执，只保存不可变操作身份、摘要和阶段。
 *
 * <p>日志不保存绝对路径、文件正文、环境变量或设备私钥。应用写入前必须先落 RUNNING，
 * 已发生副作用后的失败必须保留 UNKNOWN，供用户和运维人工核对。</p>
 */
final class ClientProjectOperationJournal {
    private static final int VERSION = 1;
    private final Path directory;
    private final ObjectMapper json;

    ClientProjectOperationJournal(Path directory, ObjectMapper json) {
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("项目操作日志目录必须是绝对路径");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.json = java.util.Objects.requireNonNull(json);
    }

    static ClientProjectOperationJournal forCurrentUser(ObjectMapper json) {
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        return new ClientProjectOperationJournal(
                home.resolve(".cm-agent").resolve("client-project-operations"), json);
    }

    /**
     * 在第一次可能写入前建立私有日志；重复操作编号不能覆盖旧回执。
     */
    void running(UUID operationId, UUID rootId, String planDigest) throws IOException {
        Files.createDirectories(directory);
        Path target = file(operationId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("该项目操作已有本地回执，禁止重复执行");
        }
        write(target, operationId, rootId, planDigest, "RUNNING");
    }

    /** 将已验证完成或未知结果推进到私有日志，不能删除旧回执。 */
    void finish(UUID operationId, String state) throws IOException {
        if (!"SUCCEEDED".equals(state) && !"FAILED".equals(state)
                && !"UNKNOWN".equals(state)) {
            throw new IllegalArgumentException("项目操作日志终态无效");
        }
        Path target = file(operationId);
        JsonNode prior = read(target);
        write(target, operationId, UUID.fromString(prior.path("rootId").asText()),
                prior.path("planDigest").asText(), state);
    }

    /** @return 原操作日志，不存在时为空 */
    JsonNode find(UUID operationId) throws IOException {
        Path target = file(operationId);
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? read(target) : null;
    }

    private JsonNode read(Path target) throws IOException {
        if (Files.isSymbolicLink(target)
                || !target.toRealPath().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("项目操作日志路径不可信");
        }
        JsonNode value = json.readTree(target.toFile());
        if (value == null || value.path("version").asInt() != VERSION
                || !value.path("operationId").asText().matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !value.path("planDigest").asText().matches("[0-9a-f]{64}")) {
            throw new IOException("项目操作日志损坏");
        }
        return value;
    }

    private void write(Path target, UUID operationId, UUID rootId, String planDigest,
            String state) throws IOException {
        if (!planDigest.matches("[0-9a-f]{64}")) throw new IOException("项目操作摘要无效");
        ObjectNode value = json.createObjectNode().put("version", VERSION)
                .put("operationId", operationId.toString()).put("rootId", rootId.toString())
                .put("planDigest", planDigest).put("state", state)
                .put("updatedAt", Instant.now().toString());
        Path temporary = directory.resolve(operationId + "-" + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(UUID operationId) {
        return directory.resolve(operationId + ".json");
    }
}
