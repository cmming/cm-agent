package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsLocalProjectDirectoryLeaseTest {
    @TempDir Path temp;

    @Test void 持有目录链期间允许子文件创建但拒绝父目录改名并在关闭后释放() throws Exception {
        Path rootPath = Files.createDirectory(temp.resolve("project"));
        Path parent = Files.createDirectory(rootPath.resolve("src"));
        var root = trusted(rootPath);
        try (var lease = WindowsLocalProjectDirectoryLease.acquire(root, parent, deadline())) {
            Files.writeString(parent.resolve("New.java"), "class New {}");
            lease.verify();
            assertThatThrownBy(() -> Files.move(parent, rootPath.resolve("moved"))).isInstanceOf(FileSystemException.class);
            assertThatThrownBy(() -> Files.move(rootPath, temp.resolve("moved-root"))).isInstanceOf(FileSystemException.class);
        }
        Files.move(parent, rootPath.resolve("moved"));
        assertThat(rootPath.resolve("moved/New.java")).isRegularFile();
    }

    @Test void 根身份不符和根外父目录在任何修改前拒绝() throws Exception {
        Path rootPath = Files.createDirectory(temp.resolve("project"));
        var root = trusted(rootPath);
        assertThatThrownBy(() -> WindowsLocalProjectDirectoryLease.acquire(root, temp, deadline()))
                .isInstanceOf(LocalProjectFileException.class);
        var wrong = new LocalProjectDirectoryTickets.Root(rootPath, "wrong");
        assertThatThrownBy(() -> WindowsLocalProjectDirectoryLease.acquire(wrong, rootPath, deadline()))
                .isInstanceOfSatisfying(LocalProjectFileException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectFileException.Reason.ROOT_CHANGED));
        Files.move(rootPath, temp.resolve("moved"));
    }

    @Test void 关闭幂等且已关闭守卫不能继续验证() throws Exception {
        Path rootPath = Files.createDirectory(temp.resolve("project"));
        var lease = WindowsLocalProjectDirectoryLease.acquire(trusted(rootPath), rootPath, deadline());
        lease.close();
        lease.close();
        assertThatThrownBy(lease::verify).isInstanceOf(LocalProjectFileException.class);
        Files.move(rootPath, temp.resolve("moved"));
    }

    private static long deadline() { return System.nanoTime() + Duration.ofSeconds(5).toNanos(); }
    private static LocalProjectDirectoryTickets.Root trusted(Path path) throws Exception {
        var tickets = new LocalProjectDirectoryTickets(Clock.systemUTC(), Duration.ofMinutes(2), 1);
        var principal = new PrincipalRef(UUID.randomUUID(), "owner", "测试", Set.of());
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(principal, request, path);
        return tickets.consume(principal, request, ticket.id());
    }
}

