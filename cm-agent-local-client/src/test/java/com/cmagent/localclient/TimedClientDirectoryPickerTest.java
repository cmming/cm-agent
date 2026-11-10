package com.cmagent.localclient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimedClientDirectoryPickerTest {
    @Test void 窗口无响应时有界返回且退出前拒绝并发选择() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var picker = new TimedClientDirectoryPicker(request -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { finished.countDown(); }
            return Optional.of(Path.of("late"));
        });
        var first = new ClientDirectoryPicker.Request("设备", "http://localhost", UUID.randomUUID(),
                Instant.now().plusMillis(300));
        assertThat(picker.choose(first)).isEmpty();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var next = new ClientDirectoryPicker.Request("设备", "http://localhost", UUID.randomUUID(),
                Instant.now().plusSeconds(5));
        try {
            assertThatThrownBy(() -> picker.choose(next)).isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
        }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
