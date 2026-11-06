package com.cmagent.localclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCommandOperationsTest {
    @TempDir Path directory;
    private final LocalCommandOperations operations = new LocalCommandOperations();

    @Test
    void 命令保留有界输出且报告真实根进程退出状态() throws Exception {
        try (var command = operations.start(fixture("output"), directory, 3)) {
            var status = command.await(Duration.ofSeconds(10));
            assertThat(status.exited()).isTrue();
            assertThat(status.exitCode()).isZero();
            assertThat(status.output()).containsExactly('a', 'b', 'c');
            assertThat(status.truncated()).isTrue();
            assertThat(status.outputComplete()).isTrue();
            byte[] copy = status.output();
            copy[0] = 0;
            assertThat(status.output()).containsExactly('a', 'b', 'c');
        }
    }

    @Test
    void 停止请求需要后续核对根进程状态() throws Exception {
        try (var command = operations.start(fixture("wait"), directory, 100)) {
            assertThat(command.await(Duration.ZERO).exited()).isFalse();
            command.requestStop();
            var status = command.await(Duration.ofSeconds(10));
            assertThat(status.stopRequested()).isTrue();
            assertThat(status.exited()).isTrue();
        }
    }

    @Test
    void 拒绝空命令和相对工作目录() {
        assertThatThrownBy(() -> operations.start(List.of(), directory, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.start(List.of("echo"), Path.of("relative"), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> fixture(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(executable, "-cp", System.getProperty("java.class.path"),
                LocalCommandFixture.class.getName(), mode);
    }
}
