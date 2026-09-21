package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.NodeEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 在有界内存中解析单技能 ZIP，并在返回结果前完成路径、类型、文本和 YAML 校验。
 *
 * <p>解析器不创建临时目录、不访问网络，也不信任 ZIP 声明大小；中央目录用于识别
 * 符号链接、加密标志和重复条目，正文大小仍按实际读取字节累计。</p>
 */
public final class SkillPackageParser {

    private static final int MAX_ARCHIVE_ENTRIES = 256;
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\n(.*?)\\n---(?:\\n|\\z)(.*)\\z", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?!-)){0,62}[a-z0-9]|[a-z0-9]");
    private final Set<String> allowedExtensions;

    /**
     * 创建解析器并固化部署环境允许的文本资源扩展名。
     *
     * @param allowedResourceTypes 配置层给出的扩展名；解析前统一转小写，
     *                             避免包内路径大小写造成同一类型判定不一致
     */
    public SkillPackageParser(Collection<String> allowedResourceTypes) {
        Objects.requireNonNull(allowedResourceTypes, "allowedResourceTypes 不能为空");
        if (allowedResourceTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedResourceTypes 不能为空");
        }
        this.allowedExtensions = allowedResourceTypes.stream()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 解析并校验一个技能 ZIP。
     *
     * @param input 上传内容流；方法只读取到 ZIP 上限加一字节
     * @param limits 当前服务端公开限制
     * @return 已冻结且可进入事务保存阶段的技能包
     * @throws SkillAccessException 包损坏、类型不支持或资源超限时抛出安全分类异常
     */
    public ParsedSkillPackage parse(InputStream input, SkillPackageLimits limits) {
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(limits, "limits 不能为空");
        try {
            byte[] archive = readArchive(input, limits.zipBytes());
            return parseArchive(archive, limits);
        } catch (SkillAccessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("技能包无法读取或结构已损坏");
        }
    }

    private ParsedSkillPackage parseArchive(byte[] archive, SkillPackageLimits limits) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> caseInsensitivePaths = new HashSet<>();
        int entries = 0;
        int expandedBytes = 0;
        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
             ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw tooLarge("技能包归档条目超过 256 个");
                }
                String path = validateArchivePath(entry.getName());
                String folded = path.toLowerCase(Locale.ROOT);
                if (!caseInsensitivePaths.add(folded)) {
                    throw invalid("技能包包含重复或大小写冲突路径");
                }
                if (entry.isUnixSymlink()) {
                    throw invalid("技能包不允许符号链接");
                }
                if (entry.getGeneralPurposeBit().usesEncryption()) {
                    throw invalid("技能包不允许加密条目");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= limits.fileCount()) {
                    throw tooLarge("技能包普通文件数量超过上限");
                }
                if (entry.getSize() < 0 || entry.getSize() == 0xffff_ffffL || !zip.canReadEntryData(entry)) {
                    throw invalid("技能包条目长度或压缩方式不可读取");
                }
                int perFileLimit = Math.max(limits.instructionBytes(), limits.resourceBytes());
                byte[] content = readEntry(zip.getInputStream(entry), perFileLimit);
                expandedBytes = Math.addExact(expandedBytes, content.length);
                if (expandedBytes > limits.expandedBytes()) {
                    throw tooLarge("技能包解压后文本总量超过上限");
                }
                files.put(path, content);
            }
        }
        if (files.isEmpty()) {
            throw invalid("技能包不能为空");
        }
        return buildPackage(files, limits);
    }

    private ParsedSkillPackage buildPackage(Map<String, byte[]> files, SkillPackageLimits limits) {
        List<String> entrypoints = files.keySet().stream()
                .filter(path -> path.equals("SKILL.md") || path.endsWith("/SKILL.md"))
                .toList();
        if (entrypoints.size() != 1) {
            throw invalid("技能包必须且只能包含一个 SKILL.md");
        }
        String entrypoint = entrypoints.getFirst();
        String root = entrypoint.substring(0, entrypoint.length() - "SKILL.md".length());
        Map<String, byte[]> relativeFiles = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!file.getKey().startsWith(root)) {
                throw invalid("技能包包含入口目录以外的文件");
            }
            String relative = file.getKey().substring(root.length());
            if (relative.isEmpty() || relative.length() > limits.pathLength()) {
                throw invalid("技能资源路径为空或超过上限");
            }
            relativeFiles.put(relative, file.getValue());
        }
        byte[] instructionBytes = relativeFiles.remove("SKILL.md");
        if (instructionBytes.length > limits.instructionBytes()) {
            throw tooLarge("SKILL.md 超过大小上限");
        }
        String instruction = decodeUtf8(instructionBytes);
        Frontmatter parsed = parseFrontmatter(instruction, limits);
        Map<String, String> resources = new LinkedHashMap<>();
        relativeFiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            validateExtension(entry.getKey());
            if (entry.getValue().length > limits.resourceBytes()) {
                throw tooLarge("技能资源超过单文件大小上限");
            }
            resources.put(entry.getKey(), decodeUtf8(entry.getValue()));
        });
        String digest = digest(parsed, resources);
        return new ParsedSkillPackage(
                parsed.name(), parsed.description(), parsed.metadata(), parsed.content(), resources, digest);
    }

    private Frontmatter parseFrontmatter(String instruction, SkillPackageLimits limits) {
        String normalized = instruction.replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = FRONTMATTER.matcher(normalized);
        if (!matcher.matches()) {
            throw invalid("SKILL.md 必须包含 YAML frontmatter 和正文");
        }
        String yamlText = matcher.group(1);
        String content = matcher.group(2).strip();
        if (content.isEmpty()) {
            throw invalid("SKILL.md 正文不能为空");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(limits.instructionBytes());
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            for (var event : yaml.parse(new StringReader(yamlText))) {
                if (event instanceof AliasEvent
                        || (event instanceof NodeEvent nodeEvent && nodeEvent.getAnchor() != null)) {
                    throw invalid("技能元数据不允许 YAML 锚点或别名");
                }
            }
            Object loaded = yaml.load(yamlText);
            if (!(loaded instanceof Map<?, ?> source)) {
                throw invalid("技能元数据必须为对象");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw invalid("技能元数据键必须为非空字符串");
                }
                metadata.put(key, validateMetadata(entry.getValue(), 0));
            }
            String name = requireString(metadata.remove("name"), "技能名称不能为空");
            String description = requireString(metadata.remove("description"), "技能描述不能为空");
            validateName(name);
            validateDescription(description);
            return new Frontmatter(name, description, metadata, content);
        } catch (SkillAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("技能 YAML 元数据不合法");
        }
    }

    private Object validateMetadata(Object value, int depth) {
        if (depth > 8) {
            throw invalid("技能元数据嵌套层级超过上限");
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(validateMetadata(item, depth + 1)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw invalid("技能元数据键必须为非空字符串");
                }
                copy.put(key, validateMetadata(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw invalid("技能元数据包含不支持的值");
    }

    private String validateArchivePath(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0
                || raw.startsWith("/") || raw.startsWith("//") || containsControl(raw)) {
            throw invalid("技能包包含不安全路径");
        }
        String candidate = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        if (candidate.isEmpty()) {
            throw invalid("技能包包含空路径");
        }
        for (String segment : candidate.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid("技能包包含路径逃逸或空路径段");
            }
        }
        return raw;
    }

    private void validateExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (allowedExtensions.stream().noneMatch(lower::endsWith)) {
            throw unsupported("技能资源类型不受支持");
        }
    }

    private void validateName(String name) {
        if (name.length() > 64 || !NAME.matcher(name).matches()) {
            throw invalid("技能名称只能使用小写字母、数字和非连续连字符");
        }
    }

    private void validateDescription(String description) {
        int codePoints = description.codePointCount(0, description.length());
        if (codePoints < 1 || codePoints > 1024 || containsControl(description)) {
            throw invalid("技能描述长度或字符不合法");
        }
    }

    private String digest(Frontmatter parsed, Map<String, String> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, parsed.name());
            update(digest, parsed.description());
            updateCanonical(digest, parsed.metadata());
            update(digest, parsed.content());
            resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private void updateCanonical(MessageDigest digest, Object value) {
        if (value instanceof Map<?, ?> map) {
            update(digest, "{");
            map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        update(digest, String.valueOf(entry.getKey()));
                        updateCanonical(digest, entry.getValue());
                    });
            update(digest, "}");
        } else if (value instanceof List<?> list) {
            update(digest, "[");
            list.forEach(item -> updateCanonical(digest, item));
            update(digest, "]");
        } else {
            update(digest, value == null ? "null" : value.getClass().getName() + ":" + value);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static byte[] readArchive(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw tooLarge("技能 ZIP 超过上传大小上限");
        }
        return bytes;
    }

    private static byte[] readEntry(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > limit) {
                    throw tooLarge("技能资源超过单文件大小上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) {
                throw invalid("技能文本不能包含 NUL");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw invalid("技能资源必须是有效 UTF-8 文本");
        }
    }

    private static String requireString(Object value, String message) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(message);
        }
        return text;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
    }

    private static SkillAccessException invalid(String message) {
        return new SkillAccessException(
                ApiErrorCode.SKILL_PACKAGE_INVALID, message, UUID.randomUUID().toString(), false);
    }

    private static SkillAccessException unsupported(String message) {
        return new SkillAccessException(
                ApiErrorCode.SKILL_RESOURCE_UNSUPPORTED, message, UUID.randomUUID().toString(), false);
    }

    private static SkillAccessException tooLarge(String message) {
        return new SkillAccessException(
                ApiErrorCode.SKILL_PACKAGE_TOO_LARGE, message, UUID.randomUUID().toString(), false);
    }

    private record Frontmatter(
            String name,
            String description,
            Map<String, Object> metadata,
            String content
    ) {
    }
}
