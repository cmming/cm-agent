package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackageParserTest {

    private static final String SKILL = "---\nname: support-guide\ndescription: 故障排查指引\n"
            + "category: support\n---\n读取 references/guide.md。";

    private final SkillPackageParser parser = new SkillPackageParser(
            List.of(".md", ".txt", ".json", ".yaml", ".yml", ".csv"));

    @Test
    void 解析单一包装目录并生成稳定摘要() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("support-guide/SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8));
        files.put("support-guide/references/guide.md", "先确认影响范围。".getBytes(StandardCharsets.UTF_8));

        ParsedSkillPackage first = parser.parse(
                new ByteArrayInputStream(zip(files, LocalDateTime.of(2026, 1, 1, 0, 0))),
                SkillPackageLimits.defaults());
        ParsedSkillPackage second = parser.parse(
                new ByteArrayInputStream(zip(files, LocalDateTime.of(2026, 2, 2, 0, 0))),
                SkillPackageLimits.defaults());

        assertThat(first.name()).isEqualTo("support-guide");
        assertThat(first.description()).isEqualTo("故障排查指引");
        assertThat(first.metadata()).containsEntry("category", "support");
        assertThat(first.content()).isEqualTo("读取 references/guide.md。");
        assertThat(first.resources()).containsOnlyKeys("references/guide.md");
        assertThat(first.sha256()).isEqualTo(second.sha256()).hasSize(64);
    }

    @Test
    void 拒绝压缩包中的父目录路径() throws Exception {
        byte[] bytes = zip(Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "../outside.txt", "不可读取".getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());

        assertCode(bytes, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
    }

    @Test
    void 拒绝未配置扩展名() throws Exception {
        byte[] bytes = zip(Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "run.sh", "echo unsafe".getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());

        assertCode(bytes, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_RESOURCE_UNSUPPORTED);
    }

    @Test
    void 接受配置开放的文本资源类型() throws Exception {
        SkillPackageParser configurableParser = new SkillPackageParser(
                List.of(".md", ".html", ".js", ".cjs", ".sh"));
        Map<String, byte[]> files = Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "page.html", "<p>说明</p>".getBytes(StandardCharsets.UTF_8),
                "helper.js", "export const name = 'support';".getBytes(StandardCharsets.UTF_8),
                "server.cjs", "module.exports = {};".getBytes(StandardCharsets.UTF_8),
                "run.sh", "echo '仅作为文本资源读取'".getBytes(StandardCharsets.UTF_8));

        ParsedSkillPackage parsed = configurableParser.parse(
                new ByteArrayInputStream(zip(files, LocalDateTime.now())), SkillPackageLimits.defaults());

        assertThat(parsed.resources()).containsOnlyKeys(
                "page.html", "helper.js", "server.cjs", "run.sh");
    }

    @Test
    void 拒绝大小写冲突和多个技能入口() throws Exception {
        byte[] caseConflict = zip(Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "Guide.md", "A".getBytes(StandardCharsets.UTF_8),
                "guide.md", "B".getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());
        byte[] twoSkills = zip(Map.of(
                "one/SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "two/SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());

        assertCode(caseConflict, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(twoSkills, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
    }

    @Test
    void 拒绝非法Utf8和超出资源限制() throws Exception {
        byte[] invalidUtf8 = zip(Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "guide.txt", new byte[]{(byte) 0xC3, (byte) 0x28}), LocalDateTime.now());
        byte[] oversized = zip(Map.of(
                "SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8),
                "guide.txt", "1234".getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());
        SkillPackageLimits tight = new SkillPackageLimits(
                1024 * 1024, 1024 * 1024, 64, 32 * 1024, 3, 240);

        assertCode(invalidUtf8, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(oversized, tight, ApiErrorCode.SKILL_PACKAGE_TOO_LARGE);
    }

    @Test
    void 拒绝YAML别名和对象标签() throws Exception {
        String alias = "---\nname: &name support-guide\ndescription: *name\n---\n正文";
        String objectTag = "---\nname: support-guide\ndescription: !!java.lang.String unsafe\n---\n正文";

        assertCode(zip(Map.of("SKILL.md", alias.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now()),
                SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(zip(Map.of("SKILL.md", objectTag.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now()),
                SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
    }

    @Test
    void 拒绝压缩包和解压总量超限() throws Exception {
        byte[] bytes = zip(Map.of("SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());

        assertCode(bytes, new SkillPackageLimits(
                bytes.length - 1, 1024 * 1024, 64, 32 * 1024, 64 * 1024, 240),
                ApiErrorCode.SKILL_PACKAGE_TOO_LARGE);
        assertCode(bytes, new SkillPackageLimits(
                1024 * 1024, 10, 64, 32 * 1024, 64 * 1024, 240),
                ApiErrorCode.SKILL_PACKAGE_TOO_LARGE);
    }

    @Test
    void 接受YAML空值并递归冻结元数据() throws Exception {
        String content = "---\nname: support-guide\ndescription: 测试\n"
                + "notes:\nprofile: {note: }\nlabels: [support]\n---\n正文";

        ParsedSkillPackage parsed = parser.parse(new ByteArrayInputStream(zip(
                Map.of("SKILL.md", content.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now())),
                SkillPackageLimits.defaults());

        assertThat(parsed.metadata()).containsEntry("notes", null);
        Map<?, ?> profile = (Map<?, ?>) parsed.metadata().get("profile");
        assertThat(profile.containsKey("note")).isTrue();
        assertThat(profile.get("note")).isNull();
        assertThatThrownBy(() -> ((List<Object>) parsed.metadata().get("labels")).add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 拒绝中央目录符号链接和重复条目() throws Exception {
        byte[] symlink = commonsZip(List.of(
                new ArchiveFile("SKILL.md", SKILL, 0),
                new ArchiveFile("references/link.md", "target.md", 0120000)));
        byte[] duplicate = commonsZip(List.of(
                new ArchiveFile("SKILL.md", SKILL, 0),
                new ArchiveFile("guide.md", "first", 0),
                new ArchiveFile("guide.md", "second", 0)));

        assertCode(symlink, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(duplicate, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
    }

    @Test
    void 拒绝加密标志和截断压缩包() throws Exception {
        byte[] valid = zip(Map.of("SKILL.md", SKILL.getBytes(StandardCharsets.UTF_8)), LocalDateTime.now());
        byte[] encrypted = valid.clone();
        setEncryptedFlag(encrypted);
        byte[] unknownLength = valid.clone();
        setUnknownCentralLength(unknownLength);

        assertCode(encrypted, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(unknownLength, SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
        assertCode(Arrays.copyOf(valid, valid.length - 8),
                SkillPackageLimits.defaults(), ApiErrorCode.SKILL_PACKAGE_INVALID);
    }

    private void assertCode(byte[] bytes, SkillPackageLimits limits, ApiErrorCode code) {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(bytes), limits))
                .isInstanceOf(SkillAccessException.class)
                .extracting("code").isEqualTo(code);
    }

    private static byte[] zip(Map<String, byte[]> files, LocalDateTime timestamp) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(timestamp);
                out.putNextEntry(zipEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] commonsZip(List<ArchiveFile> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (ArchiveFile file : files) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.path());
                if (file.unixMode() != 0) {
                    entry.setUnixMode(file.unixMode());
                }
                out.putArchiveEntry(entry);
                out.write(file.content().getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void setEncryptedFlag(byte[] zip) {
        for (int index = 0; index <= zip.length - 4; index++) {
            int signature = (zip[index] & 0xff)
                    | ((zip[index + 1] & 0xff) << 8)
                    | ((zip[index + 2] & 0xff) << 16)
                    | ((zip[index + 3] & 0xff) << 24);
            if (signature == 0x04034b50) {
                zip[index + 6] |= 0x01;
            } else if (signature == 0x02014b50) {
                zip[index + 8] |= 0x01;
            }
        }
    }

    private static void setUnknownCentralLength(byte[] zip) {
        for (int index = 0; index <= zip.length - 28; index++) {
            int signature = (zip[index] & 0xff)
                    | ((zip[index + 1] & 0xff) << 8)
                    | ((zip[index + 2] & 0xff) << 16)
                    | ((zip[index + 3] & 0xff) << 24);
            if (signature == 0x02014b50) {
                Arrays.fill(zip, index + 24, index + 28, (byte) 0xff);
                return;
            }
        }
        throw new IllegalStateException("测试 ZIP 缺少中央目录");
    }

    private record ArchiveFile(String path, String content, int unixMode) {
    }
}
