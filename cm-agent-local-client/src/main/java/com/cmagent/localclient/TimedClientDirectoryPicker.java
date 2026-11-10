package com.cmagent.localclient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 限制交互等待，不让失去响应的桌面窗口永久占住设备任务轮询。 */
final class TimedClientDirectoryPicker implements ClientDirectoryPicker {
    private final ClientDirectoryPicker delegate;
    private final AtomicBoolean busy = new AtomicBoolean();

    /** @param delegate 由本机桌面实现的确认和选择流程 */
    TimedClientDirectoryPicker(ClientDirectoryPicker delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    @Override public Optional<Path> choose(Request request) {
        long remaining = Duration.between(Instant.now(), request.expiresAt()).toMillis();
        if (remaining <= 0 || remaining > Duration.ofMinutes(5).toMillis()) return Optional.empty();
        // 遗留窗口未退出时拒绝新选择，避免两个请求同时获得可见的本机选择权。
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("上一个目录窗口尚未关闭");
        CompletableFuture<Optional<Path>> outcome = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                outcome.complete(delegate.choose(request));
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            } finally {
                busy.set(false);
            }
        }, "cm-agent-directory-selection");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            busy.set(false);
            throw failure;
        }
        try {
            Optional<Path> selected = outcome.get(remaining, TimeUnit.MILLISECONDS);
            // 即使系统窗口恰好在期限附近返回，迟到的值也不能触发目录登记。
            return request.expiresAt().isAfter(Instant.now()) ? selected : Optional.empty();
        } catch (TimeoutException expired) {
            // 不假称原生窗口已关闭；后台线程退出前保持独占，且迟到结果没有调用方可消费。
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("目录选择等待被中断", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("本机目录窗口不可用");
        }
    }
}
