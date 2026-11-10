package com.cmagent.localclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 在用户确认的项目根内形成和应用条件文件变更。
 *
 * <p>计划阶段不写文件；应用阶段会重新核对每个路径的对象身份和原文摘要，
 * 只对仍与计划一致的对象执行写入。多文件操作不能宣称原子，任何派发后异常都返回未知。</p>
 */
final class ClientProjectChangeOperations {
    static final int MAX_CHANGES = 20;
    static final int MAX_CHANGE_BYTES = 393_216;
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private final ClientProjectRootStore roots;
    private final ObjectMapper json;
    private final ClientProjectOperationJournal journal;

    ClientProjectChangeOperations(ClientProjectRootStore roots, ObjectMapper json) {
        this(roots, json, ClientProjectOperationJournal.forCurrentUser(json));
    }

    ClientProjectChangeOperations(ClientProjectRootStore roots, ObjectMapper json,
            ClientProjectOperationJournal journal) {
        this.roots = roots;
        this.json = json;
        this.journal = journal;
    }

    ObjectNode plan(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        var binding = roots.verify(identity, UUID.fromString(text(input, "rootId")));
        JsonNode source = input.get("changes");
        if (source == null || !source.isArray() || source.isEmpty()
                || source.size() > MAX_CHANGES) {
            throw new IOException("变更计划数量无效");
        }
        List<Planned> planned = new ArrayList<>();
        long total = 0;
        for (JsonNode value : source) planned.add(planOne(binding, value));
        planned.sort(Comparator.comparing(Planned::path));
        validateNoPathConflicts(planned);
        for (Planned value : planned) {
            total = Math.addExact(total, value.rawBytes());
            if (total > MAX_CHANGE_BYTES) throw new IOException("变更计划超过本机正文预算");
        }
        ObjectNode output = json.createObjectNode();
        output.put("planDigest", digest(planned));
        output.put("sourceBytes", total);
        ArrayNode changes = output.putArray("changes");
        planned.forEach(value -> changes.add(value.json()));
        return output;
    }

    ObjectNode apply(DeviceIdentityStore.StoredIdentity identity, JsonNode input)
            throws IOException {
        var binding = roots.verify(identity, UUID.fromString(text(input, "rootId")));
        JsonNode source = input.get("changes");
        if (source == null || !source.isArray() || source.isEmpty()
                || source.size() > MAX_CHANGES) {
            throw new IOException("变更执行计划无效");
        }
        String expectedDigest = text(input, "planDigest");
        UUID operationId = UUID.fromString(text(input, "operationId"));
        List<Planned> planned = new ArrayList<>();
        for (JsonNode value : source) planned.add(readPlanned(value));
        planned.sort(Comparator.comparing(Planned::path));
        validateNoPathConflicts(planned);
        if (!expectedDigest.equals(digest(planned))) throw new IOException("变更计划摘要不一致");
        String rootBefore = roots.identity(Path.of(binding.path()));
        validateCurrent(binding, planned);
        if (!roots.identity(Path.of(binding.path())).equals(rootBefore)) {
            throw new IOException("项目根在执行前发生变化");
        }
        journal.running(operationId, binding.rootId(), expectedDigest);

        boolean changed = false;
        ObjectNode observed = json.createObjectNode();
        try {
            List<Planned> executionOrder = planned.stream()
                    .sorted(Comparator.comparingInt(ClientProjectChangeOperations::executionRank))
                    .toList();
            for (Planned value : executionOrder) {
                applyOne(binding, value);
                changed = true;
                String observedPath = value.kind().equals("MOVE_FILE")
                        ? value.target() : value.path();
                ObjectNode item = observed.putObject(observedPath);
                item.put("kind", value.kind());
                Path path = value.kind().equals("DELETE_FILE")
                        || value.kind().equals("DELETE_EMPTY_DIRECTORY")
                        ? null : roots.resolve(binding, value.path());
                if (value.kind().equals("MOVE_FILE")) {
                    path = roots.resolve(binding, value.target());
                }
                item.put("after", path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        ? fileState(path).sha256() : "");
            }
            if (!roots.identity(Path.of(binding.path())).equals(rootBefore)) {
                throw new IOException("项目根在执行过程中发生变化");
            }
            journal.finish(operationId, "SUCCEEDED");
            ObjectNode output = json.createObjectNode().put("planDigest", expectedDigest);
            output.set("observed", observed);
            return output;
        } catch (IOException | RuntimeException failure) {
            if (!changed) {
                try { journal.finish(operationId, "FAILED"); }
                catch (IOException journalFailure) { failure.addSuppressed(journalFailure); }
                throw failure;
            }
            try { journal.finish(operationId, "UNKNOWN"); }
            catch (IOException journalFailure) { failure.addSuppressed(journalFailure); }
            throw new PossibleSideEffectException("部分文件变更可能已经发生", failure);
        }
    }

    private Planned planOne(ClientProjectRootStore.RootBinding binding, JsonNode input)
            throws IOException {
        String kind = text(input, "kind");
        String path = text(input, "path");
        String target = optionalText(input, "target");
        String content = optionalText(input, "content");
        String expected = optionalText(input, "expectedSha256");
        Path file = roots.resolve(binding, path);
        CurrentState before = current(file);
        switch (kind) {
            case "CREATE_FILE" -> {
                if (before.exists() || content == null) throw new IOException("新建目标已存在或缺少正文");
            }
            case "REPLACE_FILE" -> {
                requireFile(before, expected);
                if (content == null) throw new IOException("替换文件缺少正文");
            }
            case "DELETE_FILE" -> requireFile(before, expected);
            case "MOVE_FILE" -> {
                requireFile(before, expected);
                if (target == null || target.isBlank() || Files.exists(
                        roots.resolve(binding, target), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("移动目标为空或已存在");
                }
            }
            case "CREATE_DIRECTORY" -> {
                if (before.exists()) throw new IOException("目录目标已存在");
            }
            case "DELETE_EMPTY_DIRECTORY" -> requireEmptyDirectory(before);
            default -> throw new IOException("不支持的变更类型");
        }
        String after = kind.equals("DELETE_FILE") || kind.equals("DELETE_EMPTY_DIRECTORY")
                ? null : kind.equals("MOVE_FILE") || kind.equals("CREATE_DIRECTORY")
                    ? before.content() : content;
        return new Planned(kind, path, target, before.exists() ? before.sha256() : "",
                before.exists() ? before.identity() : "", sha256(after == null
                        ? new byte[0] : after.getBytes(StandardCharsets.UTF_8)),
                before.content(), after, before.content() == null ? 0
                        : before.content().getBytes(StandardCharsets.UTF_8).length,
                after == null ? 0 : after.getBytes(StandardCharsets.UTF_8).length);
    }

    private static void requireFile(CurrentState state, String expected) throws IOException {
        if (!state.exists() || !state.file()) throw new IOException("目标文件不存在");
        if (expected != null && !expected.matches("[0-9a-f]{64}")) {
            throw new IOException("目标文件版本格式无效");
        }
        if (expected != null && !expected.equals(state.sha256())) {
            throw new IOException("目标文件与计划版本不一致");
        }
    }

    private static void requireEmptyDirectory(CurrentState state) throws IOException {
        if (!state.exists() || !state.directory() || !state.empty()) {
            throw new IOException("目标不是空目录");
        }
    }

    private void validateCurrent(ClientProjectRootStore.RootBinding binding,
            List<Planned> planned) throws IOException {
        Set<String> plannedDirectories = new HashSet<>();
        planned.stream().filter(value -> value.kind().equals("CREATE_DIRECTORY"))
                .forEach(value -> plannedDirectories.add(value.path()));
        for (Planned value : planned) {
            Path path = roots.resolve(binding, value.path());
            CurrentState current = current(path);
            if (value.kind().equals("CREATE_FILE")) {
                if (current.exists()) throw new IOException("新建目标在批准后已出现");
            } else if (value.kind().equals("CREATE_DIRECTORY")) {
                if (current.exists()) throw new IOException("新建目录在批准后已出现");
                Path target = roots.resolve(binding, value.path());
                if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("新建目录父路径不可用");
                }
            } else if (value.kind().equals("MOVE_FILE")) {
                if (!current.exists() || !current.identity().equals(value.beforeIdentity())
                        || !current.sha256().equals(value.beforeSha256())) {
                    throw new IOException("移动源文件在批准后发生变化");
                }
                Path target = roots.resolve(binding, value.target());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)
                            && !plannedDirectories.contains(relativeParent(value.target()))) {
                    throw new IOException("移动目标在批准后已出现或父路径不可用");
                }
            } else if (value.kind().equals("DELETE_EMPTY_DIRECTORY")) {
                requireEmptyDirectory(current);
                if (!current.identity().equals(value.beforeIdentity())) {
                    throw new IOException("待删除目录对象在批准后发生变化");
                }
            } else if (!current.exists() || !current.identity().equals(value.beforeIdentity())
                    || !current.sha256().equals(value.beforeSha256())) {
                throw new IOException("目标文件在批准后发生变化");
            }
        }
    }

    private static String relativeParent(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    /**
     * 在任何写入前拒绝同批源/目标重复和不可安全排序的父子文件关系。
     *
     * <p>Windows 路径按不区分大小写比较；已显式计划创建的目录可以作为子动作父级，
     * 其余父子重叠会在应用中途触发部分成功，因此必须前移到零副作用计划阶段拒绝。</p>
     */
    private static void validateNoPathConflicts(List<Planned> changes) throws IOException {
        Set<String> sources = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (Planned change : changes) {
            String source = pathKey(change.path());
            if (!sources.add(source)) throw new IOException("变更源路径重复或大小写别名冲突");
        }
        for (Planned change : changes) {
            if (!change.kind().equals("MOVE_FILE")) continue;
            String target = pathKey(change.target());
            if (!targets.add(target) || sources.contains(target)) {
                throw new IOException("变更目标重复或同时作为其他动作源路径");
            }
            for (String source : sources) {
                if (isAncestor(source, target) && !isPlannedDirectory(source, changes)) {
                    throw new IOException("变更目标位于同批普通文件路径之下");
                }
            }
        }
        List<Planned> ordered = changes.stream()
                .sorted(Comparator.comparing(value -> pathKeyUntyped(value.path()))).toList();
        for (int leftIndex = 0; leftIndex < ordered.size(); leftIndex++) {
            Planned left = ordered.get(leftIndex);
            String leftPath = pathKey(left.path());
            for (int rightIndex = leftIndex + 1; rightIndex < ordered.size(); rightIndex++) {
                Planned right = ordered.get(rightIndex);
                String rightPath = pathKey(right.path());
                if (leftPath.equals(rightPath) || isAncestor(leftPath, rightPath)
                        && !left.kind().equals("CREATE_DIRECTORY")
                        || isAncestor(rightPath, leftPath)
                        && !right.kind().equals("CREATE_DIRECTORY")) {
                    throw new IOException("变更源路径存在重复、父子重叠或大小写别名");
                }
            }
        }
        String[] values = targets.toArray(String[]::new);
        for (int left = 0; left < values.length; left++) {
            for (int right = left + 1; right < values.length; right++) {
                if (isAncestor(values[left], values[right])
                        || isAncestor(values[right], values[left])) {
                    throw new IOException("同批变更目标存在父子重叠");
                }
            }
        }
    }

    private static boolean isPlannedDirectory(String path, List<Planned> changes) {
        return changes.stream().anyMatch(value -> value.kind().equals("CREATE_DIRECTORY")
                && pathKeyUntyped(value.path()).equals(path));
    }

    private static boolean isAncestor(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    private static String pathKey(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("变更路径为空");
        Path normalized = Path.of(value).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IOException("变更路径越界");
        }
        return normalized.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String pathKeyUntyped(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private void applyOne(ClientProjectRootStore.RootBinding binding, Planned value)
            throws IOException {
        Path path = roots.resolve(binding, value.path());
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException("目标父目录不可用");
        }
        switch (value.kind()) {
            case "CREATE_FILE" -> replaceAtomically(parent, path, value.after(), false);
            case "REPLACE_FILE" -> replaceAtomically(parent, path, value.after(), true);
            case "DELETE_FILE" -> {
                CurrentState current = current(path);
                if (!current.exists() || !current.identity().equals(value.beforeIdentity())
                        || !current.sha256().equals(value.beforeSha256())) {
                    throw new IOException("删除前文件版本已变化");
                }
                Files.delete(path);
            }
            case "MOVE_FILE" -> Files.move(path, roots.resolve(binding, value.target()),
                    StandardCopyOption.ATOMIC_MOVE);
            case "CREATE_DIRECTORY" -> Files.createDirectory(path);
            case "DELETE_EMPTY_DIRECTORY" -> Files.delete(path);
            default -> throw new IOException("不支持的变更类型");
        }
    }

    private static void replaceAtomically(Path parent, Path target, String content,
            boolean replace) throws IOException {
        Path temporary = Files.createTempFile(parent, ".cm-agent-", ".tmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (replace) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("文件系统不支持条件原子替换，已拒绝写入", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private CurrentState current(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new CurrentState(false, false, false, false, "", "", null);
        }
        if (Files.isSymbolicLink(path)) throw new IOException("目标为符号链接");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            boolean empty;
            try (var stream = Files.newDirectoryStream(path)) {
                empty = !stream.iterator().hasNext();
            }
            String identity = WindowsClientProjectDirectoryIdentity.read(path);
            return new CurrentState(true, false, true, empty, identity,
                    sha256(("directory:" + identity).getBytes(StandardCharsets.UTF_8)), null);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_TEXT_BYTES) {
            throw new IOException("目标不是可安全变更的文本文件");
        }
        OsState before = osState(path);
        byte[] bytes = Files.readAllBytes(path);
        OsState after = osState(path);
        if (!before.equals(after)) throw new IOException("目标文件读取期间发生变化");
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("目标文件不是有效 UTF-8", invalid);
        }
        return new CurrentState(true, true, false, false, before.identity(),
                sha256(bytes), content);
    }

    private static FileState fileState(Path path) throws IOException {
        OsState before = osState(path);
        byte[] bytes = Files.readAllBytes(path);
        if (!before.equals(osState(path))) throw new IOException("目标文件读取期间发生变化");
        return new FileState(before.identity(), before.size(), sha256(bytes));
    }

    private static OsState osState(Path path) throws IOException {
        var attributes = Files.readAttributes(path,
                java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("目标文件对象不可用");
        }
        return new OsState(WindowsClientProjectDirectoryIdentity.readFile(path),
                attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static Planned readPlanned(JsonNode value) throws IOException {
        if (!value.isObject()) throw new IOException("变更计划项无效");
        String kind = text(value, "kind");
        String path = text(value, "path");
        String target = optionalText(value, "target");
        String beforeSha = optionalText(value, "beforeSha256");
        String beforeIdentity = optionalText(value, "beforeIdentity");
        String afterSha = optionalText(value, "afterSha256");
        String before = optionalText(value, "before");
        String after = optionalText(value, "after");
        return new Planned(kind, path, target, beforeSha == null ? "" : beforeSha,
                beforeIdentity == null ? "" : beforeIdentity,
                afterSha == null ? "" : afterSha, before, after,
                before == null ? 0 : before.getBytes(StandardCharsets.UTF_8).length,
                after == null ? 0 : after.getBytes(StandardCharsets.UTF_8).length);
    }

    private static String digest(List<Planned> changes) {
        StringBuilder value = new StringBuilder("cm-agent/client-project/change/v1\n");
        for (Planned change : changes) {
            value.append(change.kind()).append('\0').append(change.path()).append('\0')
                    .append(change.target()).append('\0').append(change.beforeSha256()).append('\0')
                    .append(change.before()).append('\0').append(change.afterSha256()).append('\0')
                    .append(change.after()).append('\n');
        }
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int executionRank(Planned change) {
        return switch (change.kind()) {
            case "CREATE_DIRECTORY" -> 0;
            case "CREATE_FILE", "REPLACE_FILE", "MOVE_FILE" -> 1;
            case "DELETE_FILE" -> 2;
            case "DELETE_EMPTY_DIRECTORY" -> 3;
            default -> 4;
        };
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String text(JsonNode input, String field) throws IOException {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("变更字段无效");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode input, String field) throws IOException {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().getBytes(StandardCharsets.UTF_8).length
                > MAX_CHANGE_BYTES) throw new IOException("变更字段过大");
        return value.textValue();
    }

    /**
     * @param kind 变更动作
     * @param path 项目内源路径
     * @param target 移动文件的目标相对路径；其他动作保持为空
     * @param beforeSha256 原字节摘要
     * @param beforeIdentity 原文件对象身份
     * @param afterSha256 新字节摘要
     * @param before 原脱敏前的 UTF-8 正文
     * @param after 新 UTF-8 正文
     * @param beforeBytes 原文字节数
     * @param afterBytes 新文字节数
     */
    private record Planned(String kind, String path, String target, String beforeSha256,
            String beforeIdentity, String afterSha256, String before, String after,
            int beforeBytes, int afterBytes) {
        int rawBytes() {
            return Math.max(beforeBytes, afterBytes);
        }

        ObjectNode json() {
            ObjectNode value = new ObjectMapper().createObjectNode()
                    .put("kind", kind).put("path", path).put("target", target)
                    .put("beforeSha256", beforeSha256).put("beforeIdentity", beforeIdentity)
                    .put("afterSha256", afterSha256);
            if (before == null) value.putNull("before"); else value.put("before", before);
            if (after == null) value.putNull("after"); else value.put("after", after);
            return value;
        }
    }

    /**
     * @param exists 当前对象是否存在
     * @param file 是否为普通文件
     * @param directory 是否为目录
     * @param empty 目录是否为空
     * @param identity 当前对象身份
     * @param sha256 文件原文或目录身份摘要
     * @param content 当前 UTF-8 正文
     */
    private record CurrentState(boolean exists, boolean file, boolean directory, boolean empty,
            String identity, String sha256, String content) { }
    /** @param identity 文件对象身份 @param size 字节大小 @param sha256 原文摘要 */
    private record FileState(String identity, long size, String sha256) { }
    /** @param identity 文件对象身份 @param size 字节大小 @param modifiedMillis 最后修改毫秒 */
    private record OsState(String identity, long size, long modifiedMillis) { }

    /** 已开始写入后的失败必须由上层回报 UNKNOWN。 */
    static final class PossibleSideEffectException extends IOException {
        PossibleSideEffectException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
