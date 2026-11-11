package com.cmagent.localclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ClientProjectFenceJournalTest {
    @TempDir Path directory;
    private final ClientProjectFenceJournal.Key key = new ClientProjectFenceJournal.Key(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));

    @Test void 显式初始化后代次持久化且恢复代次不允许原副作用() throws Exception {
        Path path = directory.resolve("fence");
        var journal = new ClientProjectFenceJournal(path);
        assertThatThrownBy(() -> journal.initializeRun(key)).isInstanceOf(java.io.IOException.class);
        journal.initializeStore();
        journal.initializeRun(key);
        long initialSize = Files.size(path);
        journal.initializeRun(key);
        assertThat(Files.size(path)).isEqualTo(initialSize);
        assertThat(journal.execute(key, 1, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> journal.recover(key, 1, () -> "bad")).isInstanceOf(java.io.IOException.class);
        journal.advance(key, 1, 2);
        var restarted = new ClientProjectFenceJournal(path);
        assertThatThrownBy(() -> restarted.execute(key, 1, () -> "bad")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> restarted.execute(key, 2, () -> "bad")).isInstanceOf(java.io.IOException.class);
        assertThat(restarted.recover(key, 2, () -> "status")).isEqualTo("status");
        assertThatThrownBy(() -> restarted.initializeRun(key)).isInstanceOf(java.io.IOException.class);
        long persistedSize = Files.size(path);
        restarted.advance(key, 1, 2);
        assertThat(Files.size(path)).isEqualTo(persistedSize);
        assertThatThrownBy(() -> restarted.advance(key, 2, 4)).isInstanceOf(java.io.IOException.class);
        restarted.advance(key, 2, 3);
        assertThatThrownBy(() -> restarted.advance(key, 1, 2)).isInstanceOf(java.io.IOException.class);
    }

    @Test void 部分写入或见证丢失时拒绝执行与恢复() throws Exception {
        Path path = directory.resolve("fence");
        var journal = new ClientProjectFenceJournal(path);
        journal.initializeStore();
        journal.initializeRun(key);
        Files.writeString(path, "truncated", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> journal.execute(key, 1, () -> "bad")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> journal.advance(key, 1, 2)).isInstanceOf(java.io.IOException.class);
        Files.delete(path);
        assertThatThrownBy(() -> journal.execute(key, 1, () -> "bad"))
                .isInstanceOf(ClientProjectFenceJournal.MissingWitnessException.class);
        assertThatThrownBy(() -> journal.advance(key, 1, 2))
                .isInstanceOf(ClientProjectFenceJournal.MissingWitnessException.class);
    }

    @Test void 已确认首代缺少本机记录时不能自动迁移() throws Exception {
        var journal = new ClientProjectFenceJournal(directory.resolve("fence"));
        journal.initializeStore();
        assertThatThrownBy(() -> journal.initializeRun(key, false))
                .isInstanceOf(ClientProjectFenceJournal.MissingWitnessException.class)
                .hasMessageNotContaining(key.rootId().toString());
    }

    @Test void 恢复确认等待旧代次同步动作退出且随后旧代次被拒绝() throws Exception {
        var journal = new ClientProjectFenceJournal(directory.resolve("fence"));
        journal.initializeStore();
        journal.initializeRun(key);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var advancing = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var work = pool.submit(() -> journal.execute(key, 1, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("等待超时");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("动作中断", failure);
                }
                return "done";
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var recovery = pool.submit(() -> { advancing.countDown(); journal.advance(key, 1, 2); return null; });
            assertThat(advancing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> recovery.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(work.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            recovery.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> journal.execute(key, 1, () -> "bad")).isInstanceOf(java.io.IOException.class);
        } finally { release.countDown(); }
    }

    @Test void 不同进程也必须等待旧动作释放文件锁() throws Exception {
        Path path = directory.resolve("fence");
        Path release = directory.resolve("release");
        var journal = new ClientProjectFenceJournal(path);
        journal.initializeStore();
        journal.initializeRun(key);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(javaExecutable, "-cp", classpath, getClass().getName(),
                path.toString(), release.toString(), key.tenantId().toString(), key.deviceId().toString(),
                key.rootId().toString(), key.runId().toString(), key.serverDigest())
                .redirectErrorStream(true).start();
        try (var pool = Executors.newFixedThreadPool(2);
                var output = child.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
            var line = pool.submit(output::readLine);
            assertThat(line.get(10, TimeUnit.SECONDS)).isEqualTo("LOCKED");
            var recovery = pool.submit(() -> { journal.advance(key, 1, 2); return null; });
            assertThatThrownBy(() -> recovery.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            Files.createFile(release);
            recovery.get(10, TimeUnit.SECONDS);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThatThrownBy(() -> journal.execute(key, 1, () -> "bad")).isInstanceOf(java.io.IOException.class);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /** 子进程只用于实测系统文件锁，退出前不启动任何外部副作用。 */
    public static void main(String[] args) throws Exception {
        var key = new ClientProjectFenceJournal.Key(UUID.fromString(args[2]), UUID.fromString(args[3]),
                UUID.fromString(args[4]), UUID.fromString(args[5]), args[6]);
        new ClientProjectFenceJournal(Path.of(args[0])).execute(key, 1, () -> {
            System.out.println("LOCKED");
            System.out.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(Path.of(args[1]))) {
                if (System.nanoTime() >= deadline) throw new java.io.IOException("等待超时");
                try { Thread.sleep(10); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("等待中断", failure);
                }
            }
            return null;
        });
    }
}
