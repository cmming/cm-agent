package com.cmagent.agentscope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AgentScopeHarnessIsolationTest {
    @TempDir Path host;

    @Test
    void 每次私有空目录具有所有者权限且关闭幂等() throws Exception {
        var first = new AgentScopeHarnessIsolation();
        var second = new AgentScopeHarnessIsolation();
        try {
            assertThat(first.workspace()).isAbsolute().isNotEqualTo(second.workspace());
            try (var files = Files.list(first.workspace())) { assertThat(files.count()).isZero(); }
            var posix = Files.getFileAttributeView(first.workspace(), PosixFileAttributeView.class);
            if (posix != null) assertThat(posix.readAttributes().permissions()).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            else assertThat(Files.getFileAttributeView(first.workspace(), AclFileAttributeView.class).getAcl())
                    .allSatisfy(acl -> assertThat(acl.principal()).isEqualTo(Files.getOwner(first.workspace())));
        } finally { first.close(); second.close(); }
        first.close();
        assertThat(first.workspace()).doesNotExist();
        assertThat(second.workspace()).doesNotExist();
    }

    @Test
    void 文件系统拒绝绝对路径和越界且吞掉拒绝后仍失败() throws Exception {
        Path file = Files.writeString(host.resolve("private.txt"), "宿主内容");
        try (var isolation = new AgentScopeHarnessIsolation()) {
            assertThat(isolation.filesystem().exists(null, "AGENTS.md")).isFalse();
            Throwable first = catchThrowable(() -> isolation.filesystem().read(null, file.toString(), 0, 1));
            assertThat(first).isInstanceOf(IllegalStateException.class);
            assertThat(catchThrowable(() -> isolation.filesystem().write(null, "../private.txt", "污染"))).isSameAs(first);
            assertThat(catchThrowable(isolation::check)).isSameAs(first);
        }
        assertThat(Files.readString(file)).isEqualTo("宿主内容");
    }

    @Test
    void 空收件箱允许而发布和排队即使未订阅也记录越界() {
        try (var isolation = new AgentScopeHarnessIsolation()) {
            assertThat(isolation.bus().inboxDrain("fixture", 1).block()).isEmpty();
            assertThat(isolation.registry().findStale("fixture", Duration.ZERO).block()).isEmpty();
            isolation.check();
            var denied = isolation.bus().publish("fixture", Map.of());
            assertThatThrownBy(isolation::check).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(denied::block).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> isolation.bus().queuePush("fixture", Map.of()).block()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 后台登记被拒绝且不会吞掉错误() {
        try (var isolation = new AgentScopeHarnessIsolation()) {
            assertThatThrownBy(() -> isolation.registry().register(null).block()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(isolation::check).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 关闭只删除自有目录内符号链接不跟随宿主路径() throws Exception {
        Path file = Files.writeString(host.resolve("private.txt"), "宿主内容");
        var isolation = new AgentScopeHarnessIsolation();
        try {
            // Windows 无创建链接权限时只跳过这一操作系统能力用例；Rocky 必须实际执行。
            try { Files.createSymbolicLink(isolation.workspace().resolve("outside"), host); }
            catch (UnsupportedOperationException | java.io.IOException failure) {
                org.junit.jupiter.api.Assumptions.abort("当前系统无法创建符号链接");
            }
        } finally { isolation.close(); }
        assertThat(isolation.workspace()).doesNotExist();
        assertThat(Files.readString(file)).isEqualTo("宿主内容");
    }
}
