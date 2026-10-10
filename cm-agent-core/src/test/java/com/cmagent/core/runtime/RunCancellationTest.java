package com.cmagent.core.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class RunCancellationTest {
    @Test
    void 停止幂等且拒绝新派发() {
        RunCancellation signal = new RunCancellation();
        assertThat(signal.request()).isTrue();
        assertThat(signal.request()).isFalse();
        assertThat(signal.signal()).isCompleted();
        assertThatThrownBy(signal::enter).isInstanceOf(RunCancellation.StoppedException.class);
    }

    @Test
    void 已入场调用未退出时不能伪报停止完成() throws Exception {
        RunCancellation signal = new RunCancellation();
        var invocation = signal.enter();
        signal.request();
        try (var executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            Future<Boolean> waiting = executor.submit(() -> {
                Thread.currentThread().interrupt();
                started.countDown();
                signal.awaitQuiescence();
                return Thread.currentThread().isInterrupted();
            });
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                // 必须先释放许可再关闭 executor，否则失败断言会让测试自身永久等待。
                invocation.close();
                invocation.close();
            }
            assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            invocation.close();
        }
    }

    @Test
    void 订阅副本不能反向发布权威停止() {
        RunCancellation signal = new RunCancellation();
        signal.signal().complete(null);
        assertThat(signal.isRequested()).isFalse();
        assertThat(signal.signal()).isNotDone();
    }
}
