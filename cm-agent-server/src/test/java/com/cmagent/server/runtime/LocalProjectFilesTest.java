package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

class LocalProjectFilesTest {
    @TempDir Path temp;
    private Path project;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectFiles files;

    @BeforeEach void setup() throws Exception {
        project = Files.createDirectory(temp.resolve("project"));
        root = trusted(project);
        files = new LocalProjectFiles(List.of());
    }

    @Test void UTF8范围不切断码点且保留完整版本摘要和续读位置() throws Exception {
        Files.writeString(project.resolve("readme.md"), "中🙂x\n第二行\n", StandardCharsets.UTF_8);
        var first = files.read(root, "readme.md", 0, 4);
        assertThat(first.content()).isEqualTo("中");
        assertThat(first.nextOffset()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        var second = files.read(root, "readme.md", first.nextOffset(), 4);
        assertThat(second.content()).isEqualTo("🙂");
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.toString()).doesNotContain("🙂", "第二行", project.toString());
        assertThat(files.readLines(root, "readme.md", 2, 65536).content()).isEqualTo("第二行\n");
        assertThatThrownBy(() -> files.read(root, "readme.md", 1, 20))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(INVALID_PATH));
    }

    @Test void 空文件可预览且长行通过字节游标完整续读() throws Exception {
        Files.writeString(project.resolve("empty.txt"), "");
        var empty = files.read(root, "empty.txt", 0, 65536);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
        String text = "x".repeat(70000);
        Files.writeString(project.resolve("long.txt"), text);
        var first = files.readLines(root, "long.txt", 1, 65536);
        var second = files.read(root, "long.txt", first.nextOffset(), 65536);
        assertThat(first.content() + second.content()).isEqualTo(text);
        assertThat(second.hasMore()).isFalse();
    }

    @Test void 外部文件更新生成新版本但不会静默修改已返回对象() throws Exception {
        Files.writeString(project.resolve("a.java"), "旧正文");
        var first = files.read(root, "a.java", 0, 65536);
        Files.writeString(project.resolve("a.java"), "新正文");
        var second = files.read(root, "a.java", 0, 65536);
        assertThat(first.content()).isEqualTo("旧正文");
        assertThat(second.content()).isEqualTo("新正文");
        assertThat(first.sha256()).isNotEqualTo(second.sha256());
    }

    @Test void 目录分页稳定且游标不是文件寻址输入() throws Exception {
        for (String name : List.of("a.java", "b.java", "需求.md")) Files.writeString(project.resolve(name), name);
        var first = files.list(root, "", null, 1);
        assertThat(first.items()).extracting(LocalProjectFiles.Entry::name).containsExactly("a.java");
        assertThat(first.nextCursor()).isNotNull();
        var second = files.list(root, "", first.nextCursor(), 1);
        assertThat(second.items()).extracting(LocalProjectFiles.Entry::name).containsExactly("b.java");
        var third = files.list(root, "", second.nextCursor(), 1);
        assertThat(third.items()).extracting(LocalProjectFiles.Entry::name).containsExactly("需求.md");
        assertThat(third.nextCursor()).isNull();
        assertThatThrownBy(() -> files.list(root, "", "../outside", 1)).isInstanceOf(LocalProjectFileException.class);
    }

    @Test void 生成目录私有凭据及二进制不进入可读正文() throws Exception {
        Files.writeString(project.resolve(".env"), "合成私有内容");
        Files.createDirectory(project.resolve("target"));
        Files.write(project.resolve("image.png"), new byte[]{1,2,3});
        var page = files.list(root, "", null, 200);
        assertThat(page.items()).filteredOn(item -> item.name().equals(".env") || item.name().equals("target"))
                .allMatch(item -> item.kind() == LocalProjectFiles.Kind.BLOCKED);
        assertThatThrownBy(() -> files.read(root, ".env", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(EXCLUDED));
        assertThatThrownBy(() -> files.read(root, "image.png", 0, 1024)).isInstanceOf(LocalProjectFileException.class);
    }

    @Test void 普通源码根可包含受保护运行子树但不能进入该子树() throws Exception {
        Path privateData = Files.createDirectory(project.resolve("private-data"));
        Files.writeString(privateData.resolve("state.json"), "合成私有状态");
        Files.writeString(project.resolve("Main.java"), "class Main {}");
        var protectedFiles = new LocalProjectFiles(List.of(privateData));
        assertThat(protectedFiles.read(root, "Main.java", 0, 1024).content()).contains("class Main");
        assertThat(protectedFiles.list(root, "", null, 200).items()).filteredOn(item -> item.name().equals("private-data"))
                .allMatch(item -> item.kind() == LocalProjectFiles.Kind.BLOCKED);
        assertThatThrownBy(() -> protectedFiles.read(root, "private-data/state.json", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(EXCLUDED));
    }

    @Test void 不能通过把私有目录直接选为根来绕过相对路径排除() throws Exception {
        Path privateDirectory = Files.createDirectory(temp.resolve(".ssh"));
        Files.writeString(privateDirectory.resolve("notes.txt"), "合成私有正文");
        var privateRoot = trusted(privateDirectory);
        assertThatThrownBy(() -> files.read(privateRoot, "notes.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(EXCLUDED));
    }

    @Test void 根目录被替换后旧授权不能读取同名新目录() throws Exception {
        Files.writeString(project.resolve("a.txt"), "旧目录");
        Files.move(project, temp.resolve("original"));
        Files.createDirectory(project);
        Files.writeString(project.resolve("a.txt"), "新目录");
        assertThatThrownBy(() -> files.read(root, "a.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(ROOT_CHANGED));
    }

    @Test void 普通硬链接不能把根外文件带入项目() throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.java"), "合成根外正文");
        try { Files.createLink(project.resolve("linked.java"), outside); }
        catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "当前测试文件系统不支持创建普通硬链接");
        }
        assertThatThrownBy(() -> files.read(root, "linked.java", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(UNSAFE_FILE));
    }

    @Test void 符号链接不可作为项目文件或目录() throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.txt"), "合成根外正文");
        try { Files.createSymbolicLink(project.resolve("link.txt"), outside); }
        catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "当前测试账号或文件系统不支持创建符号链接");
        }
        assertThatThrownBy(() -> files.read(root, "link.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(UNSAFE_FILE));
    }

    @Test void 无效编码控制字符和过大文件返回具体安全分类() throws Exception {
        Files.write(project.resolve("invalid.txt"), new byte[]{(byte)0xff});
        assertThatThrownBy(() -> files.read(root, "invalid.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(INVALID_TEXT));
        Files.writeString(project.resolve("control.txt"), "x\u0085y");
        assertThatThrownBy(() -> files.read(root, "control.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(INVALID_TEXT));
        byte[] large = new byte[1048577];
        java.util.Arrays.fill(large, (byte)'a');
        Files.write(project.resolve("large.txt"), large);
        assertThatThrownBy(() -> files.read(root, "large.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(LIMIT_EXCEEDED));
    }

    @Test void 不存在路径与越界请求不泄漏宿主位置() throws Exception {
        var missing = catchThrowableOfType(() -> files.read(root, "missing.txt", 0, 1024), LocalProjectFileException.class);
        assertThat(missing.reason()).isEqualTo(NOT_FOUND);
        assertThat(missing.getMessage()).doesNotContain(temp.toString());
        assertThat(missing).hasNoCause();
        assertThatThrownBy(() -> files.read(root, "../outside.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(INVALID_PATH));
        assertThatThrownBy(() -> files.read(root, "a.txt", 0, 65537)).isInstanceOf(LocalProjectFileException.class);
        Files.createDirectory(project.resolve("folder.txt"));
        assertThatThrownBy(() -> files.read(root, "folder.txt", 0, 1024))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(UNSAFE_FILE));
    }

    @Test void 路径和正文搜索不进入排除目录且结果对象不输出正文() throws Exception {
        Files.createDirectory(project.resolve("src"));
        Files.createDirectory(project.resolve("target"));
        Files.writeString(project.resolve("src/Main.java"), "class Main { String x = \"needle\"; }");
        Files.writeString(project.resolve("target/hidden.java"), "needle");
        var result = files.search(root, "needle", true);
        assertThat(result.matches()).extracting(LocalProjectFiles.Match::relativePath).containsExactly("src/Main.java");
        assertThat(result.toString()).doesNotContain("needle", "class Main", project.toString());
        assertThat(files.search(root, "Main", false).matches()).hasSize(1);
    }

    @Test void 达到搜索命中上限时显式标记部分覆盖() throws Exception {
        for (int i = 0; i < 101; i++) Files.writeString(project.resolve(String.format("match%03d.java", i)), "合成");
        var result = files.search(root, "match", false);
        assertThat(result.matches()).hasSize(100);
        assertThat(result.partial()).isTrue();
    }

    @Test void 正文搜索受累计物理字节预算约束而不是只限制返回片段() throws Exception {
        byte[] content = new byte[1048576];
        java.util.Arrays.fill(content, (byte)'a');
        for (int i = 0; i < 10; i++) Files.write(project.resolve(String.format("file%02d.txt", i)), content);
        var result = files.search(root, "not-present", true);
        assertThat(result.matches()).isEmpty();
        assertThat(result.partial()).isTrue();
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void Windows同句柄最终路径拒绝中间目录链接而非只依赖预先字符串检查() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside-directory"));
        Files.writeString(outside.resolve("a.txt"), "合成根外正文");
        Path alias = project.resolve("alias");
        try { Files.createSymbolicLink(alias, outside); }
        catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "当前测试账号或文件系统不支持目录符号链接");
        }
        assertThatThrownBy(() -> new WindowsLocalProjectTextReader().read(alias.resolve("a.txt"), 1024,
                System.nanoTime() + Duration.ofSeconds(5).toNanos()))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(FILE_CHANGED));
    }

    @Test void 无效名称只展示转义标签且仍可跨页继续() throws Exception {
        Files.writeString(project.resolve("a\u202E.java"), "合成");
        Files.writeString(project.resolve("b.java"), "合成");
        var first = files.list(root, "", null, 1);
        assertThat(first.items().getFirst().name()).contains("\\u202e").doesNotContain("\u202E");
        assertThat(first.items().getFirst().kind()).isEqualTo(LocalProjectFiles.Kind.BLOCKED);
        assertThat(first.items().getFirst().path()).isEmpty();
        assertThat(files.list(root, "", first.nextCursor(), 1).items()).extracting(LocalProjectFiles.Entry::name).containsExactly("b.java");
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void Windows存在写句柄时拒绝读取并在关闭后正常读取() throws Exception {
        Path file = Files.writeString(project.resolve("busy.txt"), "合成");
        try (var writer = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            assertThatThrownBy(() -> files.read(root, "busy.txt", 0, 1024))
                    .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(FILE_BUSY));
        }
        assertThat(files.read(root, "busy.txt", 0, 1024).content()).isEqualTo("合成");
    }

    @Test void 过期协作预算在打开文件前失败且不回显路径() {
        assertThatThrownBy(() -> new WindowsLocalProjectTextReader().read(project.resolve("a.txt"), 1024, System.nanoTime()-1))
                .isInstanceOfSatisfying(LocalProjectFileException.class, error -> assertThat(error.reason()).isEqualTo(TIMEOUT));
    }

    private static LocalProjectDirectoryTickets.Root trusted(Path directory) throws Exception {
        // 仅测试以原生元数据构造根；正式入口必须先完成本机选择与owner票据消费。
        var tickets = new LocalProjectDirectoryTickets(Clock.systemUTC(), Duration.ofMinutes(2), 1);
        var principal = new PrincipalRef(UUID.randomUUID(), "owner", "合成主体", Set.of());
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(principal, request, directory);
        return tickets.consume(principal, request, ticket.id());
    }
}

