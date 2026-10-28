package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 仅 ssh rocky 的指定 Maven21 容器执行，合成数据库由 Testcontainers 独占创建与关闭。 */
@Testcontainers
class JdbcWorkspaceImportRepositoryTest {
    /** 合成 PostgreSQL16，生命周期由 Testcontainers 托管。 */
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 合成 MySQL8.4，生命周期由 Testcontainers 托管。 */
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL配额租约隔离与新进程恢复() throws Exception { verify(postgres); }
    @Test void MySQL配额租约隔离与新进程恢复() throws Exception { verify(mysql); }
    private void verify(JdbcDatabaseContainer<?> database) throws Exception {
        var data = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        CmAgentFlyway.configure(data).load().migrate(); var jdbc = JdbcClient.create(data);
        var rows = new JdbcWorkspaceImportRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(data)));
        var scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID());
        // TIMESTAMP(6) 两库均固定微秒，避免把数据库舍入差异当成 manifest/期限变更。
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); var limits = WorkspaceImportLimits.defaults();
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES (:id,:code,'导入合成租户',true,:now)")
                .param("id", scope.tenantId().toString()).param("code", scope.tenantId().toString()).param("now", Timestamp.from(now)).update();
        var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
        try (var pool = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> {
                start.await(); return new JdbcWorkspaceImportRepository(JdbcClient.create(data), new TransactionTemplate(new DataSourceTransactionManager(data)))
                        .reserve(draft(scope, now), limits, now);
            }));
            start.countDown(); int count = 0; for (var future : futures) if (future.get(40, TimeUnit.SECONDS)) count++;
            assertThat(count).isEqualTo(8);
        }
        var all = rows.cleanupCandidates(now.plusSeconds(3600)); assertThat(all).hasSize(8);
        var value = all.getFirst();
        for (var other : List.of(new WorkspaceImportScope(UUID.randomUUID(), "owner", scope.agentId()),
                new WorkspaceImportScope(scope.tenantId(), "other", scope.agentId()), new WorkspaceImportScope(scope.tenantId(), "owner", UUID.randomUUID())))
            assertThat(rows.find(other, value.id())).isEmpty();
        assertThat(rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now)).isEmpty();
        var lease = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.UPLOAD, Duration.ofMinutes(1), now).orElseThrow();
        assertThat(rows.complete(scope, value.id(), 1, lease.token(), limits, now)).isEmpty();
        var saved = rows.stored(scope, value.id(), value.files().getFirst().id(), 1, lease.token(), now).orElseThrow();
        var ready = rows.complete(scope, value.id(), saved.version(), lease.token(), limits, now).orElseThrow();
        rows.release(scope, value.id(), lease.token());
        var first = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now).orElseThrow();
        var second = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.READ, Duration.ofMinutes(1), now).orElseThrow();
        var revoked = rows.revoke(scope, value.id(), ready.version(), now).orElseThrow();
        rows.release(scope, value.id(), first.token());
        assertThat(rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now)).isEmpty();
        rows.release(scope, value.id(), second.token());
        var gc = rows.acquire(scope, value.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(1), now).orElseThrow();
        assertThat(rows.reserve(draft(scope, now), limits, now)).isFalse();
        assertThat(rows.reclaimed(scope, value.id(), UUID.randomUUID(), now)).isFalse();
        assertThat(rows.reclaimed(scope, value.id(), gc.token(), now)).isTrue();
        assertThat(rows.reserve(draft(scope, now), limits, now)).isTrue();
        var restarted = new JdbcWorkspaceImportRepository(JdbcClient.create(data), new TransactionTemplate(new DataSourceTransactionManager(data)));
        var restored = restarted.find(scope, value.id()).orElseThrow();
        assertThat(restored.manifestDigest()).isEqualTo(value.manifestDigest()); assertThat(restored.files()).isEqualTo(value.files());
        assertThat(restored.reclaimed()).isTrue(); assertThat(restored.state()).isEqualTo(revoked.state());
        runProbe(database, scope, restored);
        // 新来源持久元数据可保存并重建，但当前 server 运行入口仍显式拒绝，不能解释为 DEFAULT。
        var binding = new WorkspaceBinding(scope.tenantId(), scope.agentId(), UUID.randomUUID(),
                new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED, value.id()), value.manifestDigest(), "合成导入", "", 1, "owner", now, now);
        new JdbcWorkspaceSelectionRepository(jdbc).saveRun(binding);
        assertThat(new JdbcWorkspaceSelectionRepository(jdbc).run(scope.tenantId(), scope.agentId(), binding.resourceId()).orElseThrow()).isEqualTo(binding);
    }
    private static WorkspaceImport draft(WorkspaceImportScope scope, Instant now) {
        return WorkspaceImport.draft(UUID.randomUUID(), scope, "合成目录", List.of(new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 1, "a".repeat(64))), now, now.plusSeconds(3600));
    }
    private static void runProbe(JdbcDatabaseContainer<?> database, WorkspaceImportScope scope, WorkspaceImport value) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        var env = builder.environment();
        env.put("CM_IMPORT_PROBE_URL", database.getJdbcUrl()); env.put("CM_IMPORT_PROBE_USER", database.getUsername()); env.put("CM_IMPORT_PROBE_PASSWORD", database.getPassword());
        env.put("CM_IMPORT_PROBE_TENANT", scope.tenantId().toString()); env.put("CM_IMPORT_PROBE_AGENT", scope.agentId().toString());
        env.put("CM_IMPORT_PROBE_ID", value.id().toString()); env.put("CM_IMPORT_PROBE_DIGEST", value.manifestDigest());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try { assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    /** 新 JVM 不借助上一个仓储或静态缓存，连接凭据只从测试环境取得且不输出。 */
    public static final class Probe {
        /** @param args 无命令行参数，不通过参数传递数据库凭据 */
        public static void main(String[] args) {
            try {
                var data = new DriverManagerDataSource(System.getenv("CM_IMPORT_PROBE_URL"), System.getenv("CM_IMPORT_PROBE_USER"), System.getenv("CM_IMPORT_PROBE_PASSWORD"));
                var rows = new JdbcWorkspaceImportRepository(JdbcClient.create(data), new TransactionTemplate(new DataSourceTransactionManager(data)));
                var scope = new WorkspaceImportScope(UUID.fromString(System.getenv("CM_IMPORT_PROBE_TENANT")), "owner", UUID.fromString(System.getenv("CM_IMPORT_PROBE_AGENT")));
                var value = rows.find(scope, UUID.fromString(System.getenv("CM_IMPORT_PROBE_ID"))).orElseThrow();
                if (!value.reclaimed() || value.state() != WorkspaceImport.State.REVOKED || !value.manifestDigest().equals(System.getenv("CM_IMPORT_PROBE_DIGEST"))
                        || value.files().size() != 1 || value.uploaded().size() != 1) throw new IllegalStateException();
            } catch (Exception failure) { System.exit(1); }
        }
    }
}
