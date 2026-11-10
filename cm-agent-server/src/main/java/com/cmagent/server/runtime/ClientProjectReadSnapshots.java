package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RuntimeCheckpoint;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 模型在同一 Run 中首次读取文件后复用固定版本，避免审批恢复或后续分页改读外部新正文。
 *
 * <p>快照按 tenant、精确 owner 和 runId 存入现有加密检查点，不进入客户端路径或普通消息。
 * 浏览器文件夹仍读取实时磁盘，两者不混为整目录快照。</p>
 */
final class ClientProjectReadSnapshots {
    private static final String KEY = "client_project_reads_v1";
    private static final String TYPE = "cm-agent-client-project-reads-v1";
    private static final int MAX_FILES = 16;
    private static final int MAX_SOURCE_BYTES = 786_432;
    private static final int MAX_SNAPSHOT_BYTES = 2_097_152;
    private final ClientProjectService service;
    private final RuntimeCheckpointRepository checkpoints;
    private final ModelCredentialCipher cipher;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final ClientProjectProperties properties;
    private final Clock clock;

    ClientProjectReadSnapshots(ClientProjectService service,
            RuntimeCheckpointRepository checkpoints, ModelCredentialCipher cipher,
            SensitiveDataRedactor redactor, ObjectMapper json,
            ClientProjectProperties properties, Clock clock) {
        this.service = service;
        this.checkpoints = checkpoints;
        this.cipher = cipher;
        this.redactor = redactor;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /** 首发时创建空槽，使恢复能区分“尚未读取”和“原快照丢失”。 */
    synchronized void initialize(PrincipalRef principal, ClientProjectBinding binding) {
        String user = user(binding);
        if (checkpoints.find(binding.tenantId(), user, binding.runId().toString(), KEY).isPresent()) {
            verify(principal, binding);
            return;
        }
        try {
            Instant now = clock.instant();
            save(new Snapshot(binding, List.of()), null, now.plus(ttl()), now);
        } catch (Exception failure) {
            throw new IllegalStateException("客户端项目读取快照无法初始化", failure);
        }
    }

    /** @return 固定 Run 的第一次或后续分页，expectedSha256 由调用方显式引用时核对 */
    synchronized ReadPage read(PrincipalRef principal, ClientProjectBinding binding, String path,
            int offset, int maxBytes, String expectedSha256) {
        if (offset < 0 || maxBytes < 4 || maxBytes > 65_536
                || expectedSha256 != null && !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("读取范围或版本参数无效");
        }
        Loaded loaded = load(principal, binding);
        List<Frozen> files = new ArrayList<>(loaded.snapshot().files());
        Frozen file = files.stream().filter(value -> samePath(value.path(), path))
                .findFirst().orElse(null);
        if (file != null && file.deleted()) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_NOT_FOUND",
                    UUID.randomUUID().toString(), 404, "该文件已在本轮批准的变更中移除");
        }
        if (file == null) {
            file = freeze(principal, binding, path, loaded, files);
        }
        if (expectedSha256 != null && !expectedSha256.equals(file.sha256())) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_CHANGED",
                    UUID.randomUUID().toString(), 409,
                    "文件版本与该 Run 首次读取版本不一致，请刷新引用或新建下一轮");
        }
        byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
        try {
            if (offset > bytes.length || offset < bytes.length && (bytes[offset] & 0xc0) == 0x80) {
                throw new IllegalArgumentException("UTF-8 读取位置无效");
            }
            int end = Math.min(bytes.length, offset + maxBytes);
            while (end < bytes.length && (bytes[end] & 0xc0) == 0x80) end--;
            return new ReadPage(file.path(), file.sha256(), bytes.length, offset, end,
                    end < bytes.length, file.redacted(),
                    new String(bytes, offset, end - offset, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 正文搜索只使用本 Run 已冻结文件；路径搜索仍可通过设备执行实时有界扫描。
     * 因而正文搜索总带 partial，不会把未冻结的新文件悄悄混入固定版本。
     */
    synchronized Search search(PrincipalRef principal, ClientProjectBinding binding,
            String query, boolean content) {
        if (query == null || query.isBlank() || query.length() > 256) {
            throw new IllegalArgumentException("搜索关键词无效");
        }
        Snapshot snapshot = load(principal, binding).snapshot();
        List<Match> matches = new ArrayList<>();
        int skipped = 0;
        if (content) {
            for (Frozen file : snapshot.files()) {
                if (file.deleted()) continue;
                if (file.path().contains(query)) {
                    matches.add(new Match(file.path(), false, ""));
                    continue;
                }
                int index = file.content().indexOf(query);
                if (index >= 0) {
                    int start = Math.max(0, index - 80);
                    int end = Math.min(file.content().length(), index + query.length() + 160);
                    matches.add(new Match(file.path(), true,
                            file.content().substring(start, end)));
                }
                if (matches.size() >= 100) break;
            }
        }
        JsonNode live = await(principal, service.startQuery(principal, binding.projectId(),
                UUID.randomUUID(), com.cmagent.core.domain.ClientProjectTask.Kind.SEARCH,
                new ClientProjectService.ReadRequest("", "", 100, 0, 65_536,
                        query, false)));
        for (JsonNode item : live.path("matches")) {
            if (matches.size() >= 100) break;
            String path = item.path("path").asText("");
            if (path.isBlank() || matches.stream().anyMatch(value -> samePath(value.path(), path))) continue;
            matches.add(new Match(path, false, ""));
        }
        skipped += live.path("skipped").asInt(0);
        boolean partial = content || live.path("partial").asBoolean(false);
        return new Search(List.copyOf(matches), partial,
                live.path("scanned").asInt(0) + snapshot.files().size(), skipped);
    }

    /** 审批恢复只核验原槽，不读取设备最新文件补造快照。 */
    synchronized void verify(PrincipalRef principal, ClientProjectBinding binding) {
        load(principal, binding);
    }

    /** 将已核验成功的文件变更推进到当前 Run 快照，迟到读取不能回退到旧正文。 */
    synchronized void afterApply(PrincipalRef principal, ClientProjectBinding binding,
            ClientProjectService.ChangePreview preview) {
        ClientProjectService.OperationView operation = service.operation(principal,
                binding.projectId(), preview.operationId());
        if (!"SUCCEEDED".equals(operation.state())) {
            throw new IllegalStateException("变更尚未成功，不能推进读取快照");
        }
        Loaded loaded = load(principal, binding);
        List<Frozen> files = new ArrayList<>(loaded.snapshot().files());
        for (ClientProjectService.PreviewChange change : preview.changes()) {
            files.removeIf(value -> samePath(value.path(), change.path())
                    || change.target() != null && samePath(value.path(), change.target()));
            switch (change.kind()) {
                case "CREATE_FILE", "REPLACE_FILE" -> files.add(new Frozen(change.path(),
                        change.afterSha256(), change.after() == null ? "" : change.after(),
                        change.after() == null ? 0
                                : change.after().getBytes(StandardCharsets.UTF_8).length,
                        false, false));
                case "DELETE_FILE" -> files.add(new Frozen(change.path(),
                        change.beforeSha256(), "", 0, false, true));
                case "MOVE_FILE" -> {
                    files.add(new Frozen(change.path(), change.beforeSha256(), "", 0, false, true));
                    files.add(new Frozen(change.target(), change.afterSha256(),
                            change.after() == null ? "" : change.after(),
                            change.after() == null ? 0
                                    : change.after().getBytes(StandardCharsets.UTF_8).length,
                            false, false));
                }
                default -> { /* 目录动作不进入文本快照。 */ }
            }
        }
        long total = files.stream().mapToLong(Frozen::sourceBytes).sum();
        if (files.size() > MAX_FILES || total > MAX_SNAPSHOT_BYTES) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_LIMIT",
                    UUID.randomUUID().toString(), 422,
                    "写后文件快照超出本轮预算，下一轮须重新读取");
        }
        try {
            save(new Snapshot(binding, files), loaded.row(), loaded.row().expiresAt(),
                    clock.instant());
        } catch (Exception failure) {
            throw new IllegalStateException("写后读取快照无法保存", failure);
        }
    }

    /** 终态清理短时正文。 */
    synchronized void cleanup(ClientProjectBinding binding) {
        checkpoints.delete(binding.tenantId(), user(binding), binding.runId().toString(), KEY);
    }

    synchronized void cleanup(PrincipalRef principal, RunRecord run) {
        if (!principal.tenantId().equals(run.tenantId())
                || !principal.principalId().equals(run.principalId())) {
            throw new SecurityException("客户端项目快照清理归属不匹配");
        }
        checkpoints.delete(run.tenantId(), run.tenantId() + ":" + run.principalId(),
                run.id().toString(), KEY);
    }

    private Frozen freeze(PrincipalRef principal, ClientProjectBinding binding, String path,
            Loaded loaded, List<Frozen> files) {
        if (files.size() >= MAX_FILES) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_LIMIT",
                    UUID.randomUUID().toString(), 422,
                    "本 Run 固定文件快照预算已满，请拆分到下一轮");
        }
        UUID requestId = UUID.randomUUID();
        JsonNode output = await(principal, service.startQuery(principal, binding.projectId(),
                requestId, com.cmagent.core.domain.ClientProjectTask.Kind.READ,
                new ClientProjectService.ReadRequest(path, "", 1, 0, MAX_SOURCE_BYTES,
                        "", false)));
        if (output.path("hasMore").asBoolean(false)
                || output.path("totalBytes").asLong(-1) > MAX_SOURCE_BYTES) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_LIMIT",
                    UUID.randomUUID().toString(), 422,
                    "文件超过单 Run 固定快照预算，不能读取");
        }
        String original = output.path("content").asText(null);
        String sha = output.path("sha256").asText("");
        if (original == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("设备读取快照结果不完整");
        }
        String safe = redactor.redact(original);
        Frozen frozen = new Frozen(path, sha, safe, original.getBytes(StandardCharsets.UTF_8).length,
                !safe.equals(original), false);
        List<Frozen> next = new ArrayList<>(files);
        next.add(frozen);
        long total = next.stream().mapToLong(Frozen::sourceBytes).sum();
        if (total > MAX_SNAPSHOT_BYTES) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FILE_LIMIT",
                    UUID.randomUUID().toString(), 422,
                    "本 Run 固定文件快照字节预算已满，请拆分到下一轮");
        }
        try {
            save(new Snapshot(binding, next), loaded.row(), loaded.row().expiresAt(), clock.instant());
        } catch (Exception failure) {
            throw new IllegalStateException("客户端项目读取快照无法保存", failure);
        }
        files.add(frozen);
        return frozen;
    }

    private JsonNode await(PrincipalRef principal, ClientProjectService.TaskView handle) {
        long deadline = System.nanoTime() + properties.getQueryTtl().toNanos();
        while (System.nanoTime() < deadline) {
            var status = service.taskStatus(principal, handle.requestId());
            if ("SUCCEEDED".equals(status.state())) return status.output();
            if (List.of("FAILED", "UNKNOWN", "CANCELLED").contains(status.state())) {
                throw new LocalProjectAccessException("CLIENT_PROJECT_TASK_FAILED",
                        UUID.randomUUID().toString(), 503,
                        status.error() == null || status.error().isBlank()
                                ? "设备读取任务未成功" : status.error());
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("设备读取等待被中断", interrupted);
            }
        }
        service.abandonTask(principal, handle.requestId(), "Run 读取快照等待超时");
        throw new LocalProjectAccessException("CLIENT_PROJECT_TASK_TIMEOUT",
                UUID.randomUUID().toString(), 504, "设备读取任务等待超时");
    }

    private Loaded load(PrincipalRef principal, ClientProjectBinding binding) {
        try {
            RuntimeCheckpoint row = checkpoints.find(binding.tenantId(), user(binding),
                    binding.runId().toString(), KEY).orElseThrow(
                            () -> new IllegalStateException("客户端项目读取快照不存在"));
            if (row.listPayload() || !TYPE.equals(row.stateType())
                    || !row.expiresAt().isAfter(clock.instant())) {
                throw new IllegalStateException("客户端项目读取快照已失效");
            }
            Snapshot snapshot = json.readValue(cipher.decrypt(row.encryptedPayload()), Snapshot.class);
            if (!snapshot.binding().equals(binding)) {
                throw new IllegalStateException("客户端项目读取快照来源不一致");
            }
            return new Loaded(row, snapshot);
        } catch (Exception failure) {
            throw new IllegalStateException("客户端项目读取快照不可核实", failure);
        }
    }

    private void save(Snapshot snapshot, RuntimeCheckpoint prior, Instant expires, Instant now)
            throws Exception {
        checkpoints.save(new RuntimeCheckpoint(
                prior == null ? UUID.randomUUID() : prior.id(),
                snapshot.binding().tenantId(), user(snapshot.binding()),
                snapshot.binding().runId().toString(), KEY, TYPE, false,
                cipher.encrypt(json.writeValueAsString(snapshot)), expires,
                prior == null ? now : prior.createdAt(), now));
    }

    private Duration ttl() {
        return properties.getStatusRetention();
    }

    private static String user(ClientProjectBinding binding) {
        return binding.tenantId() + ":" + binding.ownerId();
    }

    private static boolean samePath(String left, String right) {
        return Normalizer.normalize(left, Normalizer.Form.NFC)
                .equals(Normalizer.normalize(right, Normalizer.Form.NFC));
    }

    record ReadPage(String path, String sha256, int totalBytes, int offset, int nextOffset,
            boolean hasMore, boolean redacted, String content) {
        @Override public String toString() {
            return "ReadPage[受保护项目文本]";
        }
    }

    record Match(String path, boolean contentMatch, String excerpt) {
        @Override public String toString() {
            return "Match[受保护搜索摘要]";
        }
    }

    record Search(List<Match> matches, boolean partial, int scanned, int skipped) {
        Search {
            matches = List.copyOf(matches);
        }
        @Override public String toString() {
            return "Search[受保护客户端项目搜索]";
        }
    }

    private record Snapshot(ClientProjectBinding binding, List<Frozen> files) {
        Snapshot {
            files = List.copyOf(files);
        }
        @Override public String toString() {
            return "Snapshot[受保护客户端项目读取]";
        }
    }

    /**
     * @param path 项目内相对路径
     * @param sha256 原文摘要
     * @param content 脱敏后的固定文本
     * @param sourceBytes 原文字节预算
     * @param redacted 是否发生脱敏
     * @param deleted 是否为本轮已确认删除的墓碑
     */
    private record Frozen(String path, String sha256, String content, int sourceBytes,
            boolean redacted, boolean deleted) {
        @Override public String toString() {
            return "Frozen[受保护客户端项目文件]";
        }
    }

    private record Loaded(RuntimeCheckpoint row, Snapshot snapshot) { }
}
