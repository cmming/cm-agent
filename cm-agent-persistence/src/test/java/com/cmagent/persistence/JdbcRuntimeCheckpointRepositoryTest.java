package com.cmagent.persistence;

import com.cmagent.core.domain.RuntimeCheckpoint;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 验证 V19 到 V20 的数据保留，以及大密文的插入、更新与租户隔离；不连接部署数据库。 */
@Testcontainers
class JdbcRuntimeCheckpointRepositoryTest {
    /** 测试类独占的 PostgreSQL，由 Testcontainers 创建和回收。 */
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 测试类独占的 MySQL，由 Testcontainers 创建和回收。 */
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void MySQL升级保留旧检查点且完整读写大密文() {
        verifyUpgrade(mysql, true);
    }

    @Test
    void PostgreSQL升级保留旧检查点且完整读写大密文() {
        verifyUpgrade(postgres, false);
    }

    private void verifyUpgrade(JdbcDatabaseContainer<?> db, boolean mysqlDialect) {
        var dataSource = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        CmAgentFlyway.configure(dataSource).target("19").load().migrate();
        var jdbc = JdbcClient.create(dataSource);
        var repository = new JdbcRuntimeCheckpointRepository(jdbc);
        UUID tenant = UUID.randomUUID(), otherTenant = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T01:00:00Z");
        for (UUID tenantId : java.util.List.of(tenant, otherTenant)) {
            jdbc.sql("""
                    INSERT INTO tenants (id, code, name, enabled, created_at)
                    VALUES (:id, :code, '检查点容量回归租户', true, :createdAt)
                    """)
                    .param("id", tenantId.toString()).param("code", tenantId.toString())
                    .param("createdAt", Timestamp.from(now)).update();
        }
        var previous = checkpoint(tenant, "旧状态密文占位", now);
        repository.save(previous);
        // Repository 只接收密文；用合成 ASCII 模拟 Base64 大载荷，不引入真实模型文本或凭据。
        String largePayload = "A".repeat(200_000);
        var large = checkpoint(tenant, largePayload, now);
        if (mysqlDialect) {
            Throwable failure = catchThrowable(() -> repository.save(large));
            assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOfSatisfying(SQLException.class, sql -> {
                assertThat(sql.getSQLState()).isEqualTo("22001");
                assertThat(sql.getErrorCode()).isEqualTo(1406);
                assertThat(sql.getMessage()).contains("encrypted_payload");
            });
        }
        assertThat(repository.find(tenant, previous.userId(), previous.sessionId(), previous.stateKey()))
                .contains(previous);
        assertThat(CmAgentFlyway.configure(dataSource).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(repository.find(tenant, previous.userId(), previous.sessionId(), previous.stateKey()))
                .contains(previous);
        // 同一槽更新不会替换 ID 或创建时间；新的状态槽走 INSERT，同样必须完整保存较大载荷。
        var updated = new RuntimeCheckpoint(previous.id(), tenant, previous.userId(), previous.sessionId(),
                previous.stateKey(), previous.stateType(), false, largePayload, now.plusSeconds(900),
                previous.createdAt(), now.plusSeconds(1));
        assertThat(repository.save(updated)).isEqualTo(updated);
        var inserted = checkpoint(tenant, largePayload, now);
        inserted = new RuntimeCheckpoint(inserted.id(), tenant, inserted.userId(), "new-session",
                inserted.stateKey(), inserted.stateType(), false, largePayload, inserted.expiresAt(), now, now);
        assertThat(repository.save(inserted)).isEqualTo(inserted);
        assertThat(repository.find(otherTenant, updated.userId(), updated.sessionId(), updated.stateKey())).isEmpty();
        repository.deleteSession(otherTenant, updated.userId(), updated.sessionId());
        assertThat(repository.find(tenant, updated.userId(), updated.sessionId(), updated.stateKey())).contains(updated);
        assertThat(repository.exists(tenant, updated.userId(), updated.sessionId())).isTrue();
        assertThat(repository.listSessionIds(tenant, updated.userId())).containsExactlyInAnyOrder("session", "new-session");
        repository.deleteSession(tenant, updated.userId(), updated.sessionId());
        assertThat(repository.exists(tenant, updated.userId(), updated.sessionId())).isFalse();
        if (mysqlDialect) {
            assertThat(jdbc.sql("""
                    SELECT DATA_TYPE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_checkpoints'
                      AND COLUMN_NAME = 'encrypted_payload'
                    """).query(String.class).single()).isEqualTo("longtext");
        }
    }

    private RuntimeCheckpoint checkpoint(UUID tenant, String payload, Instant now) {
        return new RuntimeCheckpoint(UUID.randomUUID(), tenant, tenant + ":fixture", "session", "state",
                "fixture.State", false, payload, now.plusSeconds(900), now, now);
    }
}
