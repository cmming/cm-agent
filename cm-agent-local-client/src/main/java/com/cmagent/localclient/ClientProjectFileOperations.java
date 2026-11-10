package com.cmagent.localclient;

import com.cmagent.core.runtime.LocalProjectPathPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 在客户端已登记根内执行有界目录、文本和搜索任务。
 *
 * <p>每次调用都先重新核验根对象身份；所有路径均为项目相对路径，拒绝链接、越界和
 * 应用排除目录。该执行器不调用通用绝对路径文件原语。</p>
 */
final class ClientProjectFileOperations {
    private static final long MAX_TEXT_BYTES = 2_097_152;
    private static final int MAX_ENTRIES = 10_000;
    private final ClientProjectRootStore roots;
    private final ObjectMapper json;

    ClientProjectFileOperations(ClientProjectRootStore roots, ObjectMapper json) {
        this.roots = roots;
        this.json = json;
    }

    ObjectNode list(DeviceIdentityStore.StoredIdentity identity, JsonNode input) throws IOException {
        UUID rootId = UUID.fromString(text(input, "rootId"));
        var binding = roots.verify(identity, rootId);
        String relative = optionalText(input, "path");
        String cursor = optionalText(input, "cursor");
        int limit = integer(input, "limit", 1, 200);
        Path directory = roots.resolve(binding, relative);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("项目目录不可用");
        }
        String before = directoryIdentity(directory);
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (entries.size() >= MAX_ENTRIES) throw new IOException("目录条目超过预算");
                String name = entry.getFileName().toString();
                String path = relative.isEmpty() ? name : relative + "/" + name;
                if (LocalProjectPathPolicy.excluded(path) || Files.isSymbolicLink(entry)) continue;
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) continue;
                String kind = attributes.isDirectory() ? "DIRECTORY"
                        : attributes.isRegularFile() && LocalProjectPathPolicy.textCandidate(path)
                            ? "FILE" : "BLOCKED";
                entries.add(new Entry(name, path, kind, cursor == null || name.compareTo(cursor) > 0));
            }
        }
        entries.sort(Comparator.comparing(Entry::name));
        List<Entry> selected = entries.stream().filter(Entry::afterCursor).limit(limit).toList();
        if (!directoryIdentity(directory).equals(before)) throw new IOException("目录在读取过程中发生变化");
        ObjectNode output = json.createObjectNode();
        ArrayNode items = output.putArray("items");
        selected.forEach(entry -> items.addObject().put("name", entry.name())
                .put("path", entry.path()).put("kind", entry.kind()).put("readable",
                        "FILE".equals(entry.kind())));
        String next = entries.size() > selected.size() && !selected.isEmpty()
                ? selected.getLast().name() : null;
        if (next == null) output.putNull("nextCursor"); else output.put("nextCursor", next);
        output.put("partial", entries.size() >= MAX_ENTRIES);
        return output;
    }

    ObjectNode read(DeviceIdentityStore.StoredIdentity identity, JsonNode input) throws IOException {
        var binding = roots.verify(identity, UUID.fromString(text(input, "rootId")));
        String relative = text(input, "path");
        int offset = integer(input, "offset", 0, 2_097_152);
        int maxBytes = integer(input, "maxBytes", 4, 786_432);
        Path file = roots.resolve(binding, relative);
        if (!LocalProjectPathPolicy.textCandidate(relative)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file) > MAX_TEXT_BYTES) {
            throw new IOException("项目文本文件不可读取");
        }
        FileState beforeState = fileState(file);
        byte[] bytes = Files.readAllBytes(file);
        FileState afterState = fileState(file);
        if (!beforeState.equals(afterState)) throw new IOException("项目文件在读取过程中发生变化");
        if (offset > bytes.length) throw new IOException("文本偏移越界");
        int start = clampUtf8Start(bytes, offset);
        int end = clampUtf8End(bytes, Math.min(bytes.length, start + maxBytes));
        String content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
        ObjectNode output = json.createObjectNode();
        output.put("path", relative);
        output.put("content", content);
        output.put("offset", start);
        output.put("nextOffset", end);
        output.put("totalBytes", bytes.length);
        output.put("hasMore", end < bytes.length);
        output.put("sha256", sha256(bytes));
        return output;
    }

    ObjectNode search(DeviceIdentityStore.StoredIdentity identity, JsonNode input) throws IOException {
        var binding = roots.verify(identity, UUID.fromString(text(input, "rootId")));
        String query = text(input, "query");
        if (query.length() > 256) throw new IOException("搜索关键词过长");
        boolean content = input.path("content").asBoolean(false);
        List<Match> matches = new ArrayList<>();
        int[] scanned = {0};
        int[] skipped = {0};
        Path root = bindingPath(binding);
        String before = roots.identity(root);
        searchDirectory(root, "", query, content, matches, scanned, skipped, 0);
        if (!roots.identity(root).equals(before)) throw new IOException("项目根在搜索过程中发生变化");
        ObjectNode output = json.createObjectNode();
        ArrayNode values = output.putArray("matches");
        matches.forEach(match -> values.addObject().put("path", match.path())
                .put("contentMatch", match.contentMatch()).put("excerpt", match.excerpt()));
        output.put("scanned", scanned[0]);
        output.put("skipped", skipped[0]);
        output.put("partial", scanned[0] >= 2_000 || matches.size() >= 200);
        return output;
    }

    private void searchDirectory(Path directory, String relative, String query, boolean content,
            List<Match> matches, int[] scanned, int[] skipped, int depth) throws IOException {
        if (depth > 32) {
            skipped[0]++;
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (scanned[0] >= 2_000 || matches.size() >= 200) return;
                String name = entry.getFileName().toString();
                String path = relative.isEmpty() ? name : relative + "/" + name;
                if (LocalProjectPathPolicy.excluded(path)) continue;
                scanned[0]++;
                if (Files.isSymbolicLink(entry)) {
                    skipped[0]++;
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther()) {
                    skipped[0]++;
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    searchDirectory(entry, path, query, content, matches, scanned, skipped, depth + 1);
                } else if (name.toLowerCase(java.util.Locale.ROOT)
                        .contains(query.toLowerCase(java.util.Locale.ROOT))) {
                    matches.add(new Match(path, false, ""));
                } else if (content && LocalProjectPathPolicy.textCandidate(path)
                        && Files.size(entry) <= MAX_TEXT_BYTES) {
                    String value;
                    try {
                        value = Files.readString(entry, StandardCharsets.UTF_8);
                    } catch (CharacterCodingException invalid) {
                        skipped[0]++;
                        continue;
                    }
                    int index = value.toLowerCase(java.util.Locale.ROOT)
                            .indexOf(query.toLowerCase(java.util.Locale.ROOT));
                    if (index >= 0) {
                        int start = Math.max(0, index - 80);
                        int end = Math.min(value.length(), index + query.length() + 160);
                        matches.add(new Match(path, true, value.substring(start, end)));
                    }
                }
            }
        }
    }

    private static Path bindingPath(ClientProjectRootStore.RootBinding binding) {
        return Path.of(binding.path());
    }

    private String directoryIdentity(Path directory) throws IOException {
        return roots.identity(directory);
    }

    private static FileState fileState(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("项目文件对象不可用");
        }
        return new FileState(WindowsClientProjectDirectoryIdentity.readFile(file),
                attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static int clampUtf8Start(byte[] bytes, int offset) {
        int value = offset;
        while (value < bytes.length && value > 0 && (bytes[value] & 0xc0) == 0x80) value--;
        return value;
    }

    private static int clampUtf8End(byte[] bytes, int end) {
        int value = end;
        while (value > 0 && value < bytes.length && (bytes[value] & 0xc0) == 0x80) value--;
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("任务字段无效");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > 1024) {
            throw new IllegalArgumentException("任务字段无效");
        }
        return value.textValue();
    }

    private static int integer(JsonNode input, String field, int minimum, int maximum) {
        JsonNode value = input.get(field);
        long parsed;
        if (value == null) throw new IllegalArgumentException("任务字段缺失");
        if (value.isIntegralNumber() && value.canConvertToLong()) parsed = value.longValue();
        else if (value.isTextual() && value.textValue().matches("[0-9]+")) {
            try { parsed = Long.parseLong(value.textValue()); }
            catch (NumberFormatException overflow) { throw new IllegalArgumentException("整数越界"); }
        } else throw new IllegalArgumentException("任务整数无效");
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("整数越界");
        return (int) parsed;
    }

    private record Entry(String name, String path, String kind, boolean afterCursor) { }
    private record Match(String path, boolean contentMatch, String excerpt) { }
    private record FileState(String identity, long size, long modifiedMillis) { }
}
