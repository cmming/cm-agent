package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.cmagent.server.runtime.LocalProjectChangePlanner.Kind.*;

class LocalProjectChangePlannerTest {
    @TempDir Path temp;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectFiles files;
    private LocalProjectChangePlanner planner;

    @BeforeEach void setup() throws Exception {
        var tickets = new LocalProjectDirectoryTickets(Clock.systemUTC(), Duration.ofMinutes(2), 1);
        var principal = new PrincipalRef(UUID.randomUUID(), "owner", "测试主体", Set.of());
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(principal, request, temp);
        root = tickets.consume(principal, request, ticket.id());
        files = new LocalProjectFiles(List.of());
        planner = new LocalProjectChangePlanner(files);
    }

    @Test void 创建提案只捕获新正文不生成任何文件() throws Exception {
        var request = new LocalProjectChangePlanner.Change(CREATE_FILE, "New.java", null, "class New {}", null);
        var plan = planner.plan(root, List.of(request));
        assertThat(temp.resolve("New.java")).doesNotExist();
        assertThat(plan.changes().getFirst().after()).isEqualTo("class New {}");
        assertThat(plan.changes().getFirst().before()).isEmpty();
        assertThat(plan.digest()).matches("[0-9a-f]{64}");
        assertThat(plan.toString()).doesNotContain("class New", temp.toString());
    }

    @Test void 编辑差异冻结原文摘要但不修改真实文件() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "原文");
        var old = files.read(root, "a.txt", 0, 1024);
        var plan = planner.plan(root, List.of(new LocalProjectChangePlanner.Change(REPLACE_FILE, "a.txt", null, "新文", old.sha256())));
        assertThat(plan.changes().getFirst().before()).isEqualTo("原文");
        assertThat(plan.changes().getFirst().after()).isEqualTo("新文");
        assertThat(plan.changes().getFirst().beforeVersion()).isEqualTo(old.sha256());
        Files.writeString(temp.resolve("a.txt"), "外部编辑");
        assertThat(plan.changes().getFirst().before()).isEqualTo("原文");
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(
                REPLACE_FILE, "a.txt", null, "新文", old.sha256()))))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.CONFLICT));
        assertThat(Files.readString(temp.resolve("a.txt"))).isEqualTo("外部编辑");
    }

    @Test void 删除与移动提案不产生副作用且目标存在时明确冲突() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "合成原文");
        String sha = files.read(root, "a.txt", 0, 1024).sha256();
        var deletion = planner.plan(root, List.of(new LocalProjectChangePlanner.Change(DELETE_FILE, "a.txt", null, null, sha)));
        assertThat(deletion.changes().getFirst().before()).isEqualTo("合成原文");
        assertThat(temp.resolve("a.txt")).exists();
        var move = planner.plan(root, List.of(new LocalProjectChangePlanner.Change(MOVE_FILE, "a.txt", "b.txt", null, sha)));
        assertThat(move.changes().getFirst().target()).isEqualTo("b.txt");
        assertThat(temp.resolve("a.txt")).exists();
        assertThat(temp.resolve("b.txt")).doesNotExist();
        Files.writeString(temp.resolve("b.txt"), "另一文件");
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(MOVE_FILE, "a.txt", "b.txt", null, sha))))
                .isInstanceOf(LocalProjectChangeException.class);
    }

    @Test void 目录提案只支持新建或当前空目录不递归删除() throws Exception {
        var create = planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_DIRECTORY, "new", null, null, null)));
        assertThat(create.changes()).hasSize(1);
        assertThat(temp.resolve("new")).doesNotExist();
        Path empty = Files.createDirectory(temp.resolve("empty"));
        var deletion = planner.plan(root, List.of(new LocalProjectChangePlanner.Change(DELETE_EMPTY_DIRECTORY, "empty", null, null, null)));
        assertThat(deletion.changes().getFirst().beforeVersion()).matches("[0-9a-f]{64}");
        assertThat(empty).isDirectory();
        Files.writeString(empty.resolve("child.txt"), "合成");
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(
                DELETE_EMPTY_DIRECTORY, "empty", null, null, null)))).isInstanceOf(LocalProjectFileException.class);
    }

    @Test void 同批路径大小写别名及父子关系需要拆分而不是隐式覆盖() {
        var first = new LocalProjectChangePlanner.Change(CREATE_DIRECTORY, "folder", null, null, null);
        var child = new LocalProjectChangePlanner.Change(CREATE_FILE, "folder/a.txt", null, "x", null);
        assertThatThrownBy(() -> planner.plan(root, List.of(first, child)))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.OVERLAP));
        assertThatThrownBy(() -> planner.plan(root, List.of(
                new LocalProjectChangePlanner.Change(CREATE_FILE, "a.java", null, "x", null),
                new LocalProjectChangePlanner.Change(CREATE_FILE, "A.java", null, "y", null))))
                .isInstanceOf(LocalProjectChangeException.class);
        assertThat(temp.resolve("folder")).doesNotExist();
    }

    @Test void 私有路径和不支持类型不能进入变更意图() {
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, ".env", null, "x", null))))
                .isInstanceOf(LocalProjectFileException.class);
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, "image.png", null, "x", null))))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.UNSUPPORTED));
    }

    @Test void 单文件整批数量和编码均有界() throws Exception {
        var many = new ArrayList<LocalProjectChangePlanner.Change>();
        for (int i = 0; i < 21; i++) many.add(new LocalProjectChangePlanner.Change(CREATE_FILE, "a" + i + ".txt", null, "x", null));
        assertThatThrownBy(() -> planner.plan(root, many)).isInstanceOf(LocalProjectChangeException.class);
        String large = "中".repeat(400000);
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, "a.txt", null, large, null))))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.LIMIT));
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, "a.txt", null, "a\u0000b", null))))
                .isInstanceOf(LocalProjectFileException.class);
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, "a.txt", null, "\uD800", null))))
                .isInstanceOf(LocalProjectFileException.class);
        try (var entries = Files.list(temp)) { assertThat(entries.count()).isZero(); }
    }

    @Test void 精确摘要稳定且字段边界和内容变化会改变摘要() throws Exception {
        var first = new LocalProjectChangePlanner.Change(CREATE_FILE, "a.txt", null, "a\nb", null);
        var plan = planner.plan(root, List.of(first));
        assertThat(planner.plan(root, List.of(first)).digest()).isEqualTo(plan.digest());
        assertThat(planner.plan(root, List.of(new LocalProjectChangePlanner.Change(CREATE_FILE, "a.txt", null, "a\nc", null))).digest())
                .isNotEqualTo(plan.digest());
        assertThatThrownBy(() -> plan.changes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(plan.changes().getFirst().toString()).doesNotContain("a\nb");
    }

    @Test void 已有同内容替换明确表示无需执行() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "合成");
        String sha = files.read(root, "a.txt", 0, 1024).sha256();
        assertThatThrownBy(() -> planner.plan(root, List.of(new LocalProjectChangePlanner.Change(REPLACE_FILE, "a.txt", null, "合成", sha))))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.NO_CHANGE));
    }
}

