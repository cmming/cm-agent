package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.persistence.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 在 Rocky 隔离数据库中核实检查点容量与文件交付边界。
 *
 * <p>固定 Python 真正生成 DOCX，再用合成 State 经过现有序列化、加密和 JDBC 保存链路。
 * MySQL 超限后调用实际 Run 失败收口与文件清理；PostgreSQL 验证完整状态恢复后成功发布。
 * 此验证不调用真实模型，不修改历史迁移，也不替代真实模型与执行编排的端到端验收。</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_SANDBOX", matches = "true")
class SkillArtifactCheckpointIntegrationTest {
    /** 每类独占的临时 PostgreSQL，生命周期由 Testcontainers 管理。 */
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 每类独占的临时 MySQL，不连接部署者数据库。 */
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    /** 测试结束后自动回收的私有文件卷。 */
    @TempDir
    Path volume;

    @Test
    void MySQL检查点超限保留原状态且文件不可交付并完成清理() throws Exception {
        verify(mysql, true);
    }

    @Test
    void PostgreSQL较大检查点完整恢复后允许文件交付() throws Exception {
        verify(postgres, false);
    }

    private void verify(JdbcDatabaseContainer<?> db, boolean limited) throws Exception {
        var properties = new SkillProperties();
        properties.getSandbox().setEnabled(true);
        var policy = properties.getSandbox().getArtifacts();
        policy.setEnabled(true);
        policy.setRootDirectory(volume.toString());
        policy.setEncryptionKey(Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)));
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        CmAgentFlyway.configure(ds).load().migrate();
        var jdbc = JdbcClient.create(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), model = UUID.randomUUID(), run = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'容量验收租户',true,:now)")
                .param("id", tenant.toString()).param("code", tenant.toString()).param("now", now).update();
        // 仅满足 Agent 的模型外键，没有认证材料；此测试从不向占位地址发送请求。
        jdbc.sql("INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at) VALUES(:id,:tenant,'OPENAI','容量隔离占位','http://127.0.0.1:1','fixture','',true,:now)")
                .param("id", model.toString()).param("tenant", tenant.toString()).param("now", now).update();
        new JdbcAgentDefinitionRepository(jdbc, new ObjectMapper(), tx).save(new AgentDefinition(
                agent, tenant, "检查点验收 Agent", "固定脚本", "", model, "fixture", 0.1, 5, true,
                List.of(), "fixture", "fixture"));
        var principal = new PrincipalRef(tenant, "fixture", "隔离验收", Set.of("agent:read"));
        var runs = new JdbcRunRepository(jdbc);
        var running = RunRecord.create(run, tenant, agent, principal.principalId(), "生成 DOCX", Instant.now());
        runs.save(tenant, running);
        var rows = new JdbcSkillArtifactRepository(jdbc, tx);
        var storage = new FileSystemSkillArtifactStorage(policy);
        var beans = new StaticListableBeanFactory();
        beans.addBean("storage", storage);
        var audit = new AuditAppender(new JdbcAuditEventRepository(jdbc, tx));
        var artifacts = new SkillArtifactService(rows, beans.getBeanProvider(SkillArtifactStorage.class), runs,
                new JdbcSkillTrialRepository(jdbc, tx), new JdbcConversationRepository(jdbc),
                (owner, permission) -> owner.permissions().contains(permission)
                        ? AuthorizationDecision.allow() : AuthorizationDecision.deny("权限不足"), audit, properties);
        var request = new SkillReadRequest(principal, agent, run, "checkpoint-call", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "scripts/main.py");
        // 执行、传输和清理固定于同一个句柄；只有正常关闭后才开始检查点保存阶段。
        try (var collection = artifacts.begin(request);
             var execution = new DockerSkillSandbox(properties.getSandbox()).open(request, Map.of())) {
            execution.execute(Map.of("scripts/main.py", Files.readString(
                    Path.of("src/test/resources/skill-artifacts/scripts/main.py"))), "", collection);
            collection.complete();
        }
        var artifact = rows.list(tenant, run).getFirst();
        assertThat(artifact.status()).isEqualTo(SkillArtifactStatus.PENDING);
        assertThat(artifacts.list(principal, agent, run, null).files()).isEmpty();
        var checkpoints = new JdbcRuntimeCheckpointRepository(jdbc);
        var cipher = new ModelCredentialCipher(new SecretKeySpec(new SecureRandom().generateSeed(32), "AES"));
        var stateStore = new RepositoryAgentStateStore(checkpoints, cipher, new ObjectMapper(),
                Clock.systemUTC(), Duration.ofMinutes(15));
        String user = tenant + ":" + principal.principalId();
        var original = new CheckpointState("原始状态".repeat(1000));
        var large = new CheckpointState("x".repeat(50_000));
        stateStore.save(user, run.toString(), "state", original);
        assertThat(cipher.encrypt(new ObjectMapper().writeValueAsString(large)).getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(65_535);
        var persistence = new RunPersistenceService(runs, new JdbcToolCallRepository(jdbc, tx), audit,
                new SensitiveDataRedactor(), tx);
        persistence.configureArtifacts(artifacts);
        if (limited) {
            assertThat(jdbc.sql("SELECT CHARACTER_OCTET_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='runtime_checkpoints' AND COLUMN_NAME='encrypted_payload'")
                    .query(Long.class).single()).isEqualTo(65_535L);
            // 捕获 UPDATE 和首次 INSERT 的实际驱动失败，不输出 SQL、异常消息或合成状态正文。
            assertCapacityFailure(catchThrowable(() -> stateStore.save(user, run.toString(), "state", large)));
            assertCapacityFailure(catchThrowable(() -> stateStore.save(user, run.toString(), "new-state", large)));
            assertThat(stateStore.get(user, run.toString(), "state", CheckpointState.class)).contains(original);
            assertThat(checkpoints.find(tenant, user, run.toString(), "new-state")).isEmpty();
            var failed = persistence.completeFailure(principal, running);
            persistence.appendFailureAudit(principal, failed);
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(artifacts.list(principal, agent, run, null).files()).isEmpty();
            assertThatThrownBy(() -> artifacts.download(principal, artifact.id()))
                    .isInstanceOfSatisfying(SkillAccessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ARTIFACT_NOT_FOUND));
            artifacts.cleanup();
            assertThat(rows.find(tenant, artifact.id()).orElseThrow().status()).isEqualTo(SkillArtifactStatus.DELETED);
            assertThatThrownBy(() -> storage.readVerified(artifact)).isInstanceOf(java.io.IOException.class);
        } else {
            stateStore.save(user, run.toString(), "state", large);
            assertThat(stateStore.get(user, run.toString(), "state", CheckpointState.class)).contains(large);
            assertThat(checkpoints.find(UUID.randomUUID(), user, run.toString(), "state")).isEmpty();
            persistence.complete(principal, running, new AgentRunResult(run, RunStatus.SUCCEEDED, "生成固定文档",
                    List.of(), running.startedAt(), Instant.now(), ""), List.of());
            assertThat(artifacts.list(principal, agent, run, null).files()).hasSize(1);
            try (var download = artifacts.download(principal, artifact.id())) {
                var bytes = new ByteArrayOutputStream();
                download.write(bytes);
                assertThat((long) bytes.size()).isEqualTo(artifact.sizeBytes());
            }
        }
    }

    /** 仅检查当前驱动的字段容量错误；原始异常从不进入日志或前端。 */
    private static void assertCapacityFailure(Throwable failure) {
        assertThat(failure).isInstanceOf(DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(SQLException.class);
        var sql = (SQLException) root;
        assertThat(sql.getSQLState()).isEqualTo("22001");
        assertThat(sql.getErrorCode()).isEqualTo(1406);
        assertThat(sql.getMessage()).contains("encrypted_payload");
    }

    /**
     * 只用于隔离验收的合成状态，不包含技能正文、模型响应或真实凭据。
     *
     * @param value 构造容量边界所需的合成文本，测试不输出该文本
     */
    public record CheckpointState(String value) implements State {
    }
}
