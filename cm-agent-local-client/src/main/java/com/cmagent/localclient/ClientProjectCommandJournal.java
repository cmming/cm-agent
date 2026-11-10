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
 * 当前 OS 用户私有的命令回执，只保存命令编号、根、摘要、状态和退出码。
 *
 * <p>进程启动前先持久化 RUNNING；客户端重启发现仍为 RUNNING 或 STOP_REQUESTED 时只改为
 * UNKNOWN，不尝试接管、扫描或重启旧进程。命令参数、环境、输出和绝对路径均不写入本文件。</p>
 */
final class ClientProjectCommandJournal {
    private static final int VERSION = 1;
    private final Path directory;
    private final ObjectMapper json;

    ClientProjectCommandJournal(Path directory, ObjectMapper json) {
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("命令回执目录必须为绝对路径");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.json = java.util.Objects.requireNonNull(json);
        try {
            recoverInterrupted();
        } catch (IOException failure) {
            throw new IllegalStateException("命令回执恢复失败", failure);
        }
    }

    static ClientProjectCommandJournal forCurrentUser(ObjectMapper json) {
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        return new ClientProjectCommandJournal(
                home.resolve(".cm-agent").resolve("client-project-commands"), json);
    }

    synchronized void running(UUID commandId, UUID rootId, String planDigest)
            throws IOException {
        if (Files.exists(file(commandId), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("该命令已有本地回执，禁止重复执行");
        }
        write(commandId, rootId, planDigest, "RUNNING", null, false);
    }

    synchronized void update(UUID commandId, String state, Integer exitCode,
            boolean stopRequested) throws IOException {
        if (!java.util.Set.of("RUNNING", "STOP_REQUESTED", "SUCCEEDED", "FAILED",
                "CANCELLED", "UNKNOWN").contains(state)) {
            throw new IOException("命令回执状态无效");
        }
        JsonNode prior = find(commandId);
        if (prior == null) throw new IOException("命令回执不存在");
        write(commandId, UUID.fromString(prior.path("rootId").asText()),
                prior.path("planDigest").asText(), state, exitCode, stopRequested);
    }

    synchronized JsonNode find(UUID commandId) throws IOException {
        Path target = file(commandId);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(target) || !target.toRealPath()
                .equals(target.toAbsolutePath().normalize())) {
            throw new IOException("命令回执路径不可信");
        }
        JsonNode value = json.readTree(target.toFile());
        if (value == null || value.path("version").asInt() != VERSION
                || !commandId.toString().equals(value.path("operationId").asText())
                || !value.path("planDigest").asText().matches("[0-9a-f]{64}")
                || !value.path("state").isTextual()) {
            throw new IOException("命令回执损坏");
        }
        return value;
    }

    private void recoverInterrupted() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(value -> value.getFileName().toString()
                    .matches("[0-9a-f-]{36}\\.json")).toList()) {
                UUID id;
                try {
                    id = UUID.fromString(path.getFileName().toString().replace(".json", ""));
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                JsonNode value = find(id);
                if (value != null && java.util.Set.of("RUNNING", "STOP_REQUESTED")
                        .contains(value.path("state").asText())) {
                    write(id, UUID.fromString(value.path("rootId").asText()),
                            value.path("planDigest").asText(), "UNKNOWN", null,
                            value.path("stopRequested").asBoolean(false));
                }
            }
        }
    }

    private void write(UUID commandId, UUID rootId, String digest, String state,
            Integer exitCode, boolean stopRequested) throws IOException {
        Files.createDirectories(directory);
        if (!digest.matches("[0-9a-f]{64}")) throw new IOException("命令摘要无效");
        ObjectNode value = json.createObjectNode().put("version", VERSION)
                .put("operationId", commandId.toString()).put("rootId", rootId.toString())
                .put("planDigest", digest).put("state", state)
                .put("stopRequested", stopRequested)
                .put("updatedAt", Instant.now().toString());
        if (exitCode == null) value.putNull("exitCode"); else value.put("exitCode", exitCode);
        Path target = file(commandId);
        Path temporary = directory.resolve(commandId + "-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(json.writeValueAsBytes(value)));
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(UUID commandId) {
        return directory.resolve(commandId + ".json");
    }
}
