package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** 仅ssh rocky指定Maven21容器执行；合成数据库及新JVM由测试独占，不操作现有业务库。 */
@Testcontainers
class JdbcLocalProjectRepositoryTest {
    /** Testcontainers托管的PostgreSQL16，仅本测试使用。 */
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** Testcontainers托管的MySQL8.4，仅本测试使用。 */
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test void PostgreSQL项目隔离并发来源操作与新JVM恢复() throws Exception { verify(postgres); }
    @Test void MySQL项目隔离并发来源操作与新JVM恢复() throws Exception { verify(mysql); }

    private void verify(JdbcDatabaseContainer<?> database) throws Exception {
        var data = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        CmAgentFlyway.configure(data).load().migrate();
        var jdbc = JdbcClient.create(data);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
        var rows = new JdbcLocalProjectRepository(jdbc, tx);
        var bindings = new JdbcLocalProjectBindingRepository(jdbc, tx);
        var operations = new JdbcLocalProjectOperationRepository(jdbc, tx);
        UUID tenant = UUID.randomUUID(), model = UUID.randomUUID(), agent = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seed(jdbc, tenant, model, agent, now);
        var root = new LocalProjectRoot(UUID.randomUUID(), tenant, "Owner", "local-node",
                encrypted("F:/synthetic/project"), "a".repeat(64), now);
        var project = new LocalProject(UUID.randomUUID(), tenant, "Owner", "我的项目", "local-node",
                root.id(), root.identity(), LocalProject.State.ACTIVE, 1, now, now);
        Boolean inserted = tx.execute(status -> {
            assertThat(rows.insertRoot(root)).isTrue();
            assertThat(rows.insertRoot(root)).isFalse();
            assertThat(rows.insert(project)).isTrue();
            assertThat(rows.insert(project)).isFalse();
            return rows.find(tenant, "Owner", project.id()).isPresent();
        });
        assertThat(inserted).isTrue();
        assertThat(rows.root(tenant, "Owner", root.id())).contains(root);
        for (String other : List.of("owner", "Owner ", "another")) {
            assertThat(rows.find(tenant, other, project.id())).isEmpty();
            assertThat(rows.root(tenant, other, root.id())).isEmpty();
            assertThat(rows.list(tenant, other, new LocalProjectPageRequest(1, null, null), true)).isEmpty();
        }
        assertThat(rows.find(UUID.randomUUID(), "Owner", project.id())).isEmpty();
        assertThat(rows.list(tenant, "Owner", new LocalProjectPageRequest(1, null, null), false)).containsExactly(project);
        var mismatched = new LocalProject(UUID.randomUUID(), tenant, "owner", "不应借用他人根", root.nodeId(),
                root.id(), root.identity(), LocalProject.State.ACTIVE, 1, now, now);
        assertThat(rows.insert(mismatched)).isFalse();

        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "私有项目会话", "Owner", now, now);
        var conversations = new JdbcConversationRepository(jdbc);
        conversations.save(tenant, conversation);
        var conversationBinding = new LocalProjectBinding(tenant, "Owner", project.id(), agent, conversation.id(), null,
                root.id(), root.identity(), root.nodeId(), project.version(), now);
        assertThat(bindings.insertConversation(conversationBinding)).isTrue();
        assertThat(bindings.insertConversation(conversationBinding)).isFalse();
        assertThat(bindings.conversation(tenant, "owner", conversation.id())).isEmpty();
        assertThat(bindings.conversation(tenant, "Owner", conversation.id())).contains(conversationBinding);
        assertThat(jdbc.sql("SELECT workspace_protocol FROM conversations WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", conversation.id().toString()).query(Integer.class).single()).isEqualTo(4);

        var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "合成私有输入", now);
        var runs = new JdbcRunRepository(jdbc);
        runs.save(tenant, run);
        var runBinding = new LocalProjectBinding(tenant, "Owner", project.id(), agent, conversation.id(), run.id(),
                root.id(), root.identity(), root.nodeId(), project.version(), now);
        assertThat(bindings.insertRun(runBinding)).isTrue();
        assertThat(bindings.insertRun(runBinding)).isFalse();
        assertThat(bindings.run(tenant, "owner", run.id())).isEmpty();
        // T2仅提供持久化，Web/Runtime尚未接线项目授权；旧可见列表必须先隐藏新协议，不能退回旧来源。
        assertThat(conversations.listVisible(tenant, agent, "Owner", new ConversationPageRequest(10, null, null))).isEmpty();
        assertThat(runs.listVisible(tenant, agent, "owner", new RunPageRequest(10, null, null))).isEmpty();
        assertThat(runs.listVisible(tenant, agent, "Owner", new RunPageRequest(10, null, null))).isEmpty();
        assertThat(runs.workspaceProtocol(tenant, agent, run.id())).isEqualTo(LocalProjectBinding.PROTOCOL);
        // 即使会话头仍为旧来源，只要历史关联私有项目Run，也不能从旧列表或详情校验绕回公开规则。
        var derived = new Conversation(UUID.randomUUID(), tenant, agent, "派生私有标题", "another", now, now);
        conversations.save(tenant, derived);
        var messages = new JdbcConversationMessageRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), tx);
        messages.append(tenant, new ConversationMessageDraft(UUID.randomUUID(), tenant, derived.id(), MessageRole.USER,
                "合成项目关联", List.of(MessageContentBlock.text("合成项目关联")), run.id(), now));
        var derivedRun = RunRecord.create(UUID.randomUUID(), tenant, agent, "another", "派生私有输入", now);
        runs.save(tenant, derivedRun);
        messages.append(tenant, new ConversationMessageDraft(UUID.randomUUID(), tenant, derived.id(), MessageRole.USER,
                "合成后续", List.of(MessageContentBlock.text("合成后续")), derivedRun.id(), now));
        assertThat(runs.visibleConversationFromWorkspace(derived, "another")).isFalse();
        assertThat(conversations.listVisible(tenant, agent, "another", new ConversationPageRequest(10, null, null))).isEmpty();
        assertThat(runs.listVisible(tenant, agent, "another", new RunPageRequest(10, null, null))).isEmpty();

        var operation = new LocalProjectOperation(UUID.randomUUID(), tenant, "Owner", project.id(), agent,
                conversation.id(), run.id(), LocalProjectOperation.Kind.COMMAND, "b".repeat(64), encrypted("合成命令清单"),
                LocalProjectOperation.State.PROPOSED, 1, now, now);
        assertThat(operations.insertOperation(operation)).isTrue();
        assertThat(operations.insertOperation(operation)).isFalse();
        assertThat(operations.operation(tenant, "owner", operation.id())).isEmpty();
        assertThat(operations.advance(tenant, "owner", operation.id(), 1, LocalProjectOperation.State.WAITING_APPROVAL, now)).isEmpty();
        assertThat(operations.advance(tenant, "Owner", operation.id(), 1, LocalProjectOperation.State.WAITING_APPROVAL, now)).isPresent();
        assertThat(operations.advance(tenant, "Owner", operation.id(), 2, LocalProjectOperation.State.PREPARED, now)).isPresent();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var outcomes = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 16; i++) outcomes.add(pool.submit(() -> operations.advance(tenant, "Owner", operation.id(),
                    3, LocalProjectOperation.State.RUNNING, now).isPresent()));
            int accepted = 0;
            for (var outcome : outcomes) if (outcome.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(operations.advance(tenant, "Owner", operation.id(), 4, LocalProjectOperation.State.UNKNOWN, now)).isPresent();
        assertThatThrownBy(() -> operations.advance(tenant, "Owner", operation.id(), 5, LocalProjectOperation.State.RUNNING, now))
                .isInstanceOf(IllegalStateException.class);
        assertThat(operations.operation(tenant, "Owner", operation.id()).orElseThrow().state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(operations.advance(tenant, "Owner", operation.id(), 5, LocalProjectOperation.State.FAILED, now)).isPresent();

        var before = rows.find(tenant, "Owner", project.id()).orElseThrow();
        assertThat(rows.rename(tenant, "owner", project.id(), 1, "拒绝", now.plusSeconds(1))).isFalse();
        assertThat(rows.rename(tenant, "Owner", project.id(), 1, "新名称", now.plusSeconds(1))).isTrue();
        assertThat(rows.rename(tenant, "Owner", project.id(), 1, "旧版本", now.plusSeconds(2))).isFalse();
        assertThat(rows.find(tenant, "Owner", project.id()).orElseThrow().rootBindingId()).isEqualTo(before.rootBindingId());
        assertThat(rows.archive(tenant, "Owner", project.id(), 2, now.plusSeconds(2))).isTrue();
        assertThat(rows.list(tenant, "Owner", new LocalProjectPageRequest(10, null, null), false)).isEmpty();
        assertThat(rows.list(tenant, "Owner", new LocalProjectPageRequest(10, null, null), true)).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM permissions WHERE code IN ('project:read','project:write','project:execute','project:manage')")
                .query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM role_permissions WHERE permission_code LIKE 'project:%'").query(Long.class).single()).isZero();
        verifyRollback(jdbc, tx, rows, bindings, operations, tenant, agent, now);
        probe(database, tenant, project.id(), root.id(), operation.id());
    }

    /** 外层服务最终审计失败时，新增根/项目/绑定/操作及来源见证必须一起回滚，不留下半个可执行意图。 */
    private static void verifyRollback(JdbcClient jdbc, TransactionTemplate tx, JdbcLocalProjectRepository rows,
            JdbcLocalProjectBindingRepository bindings, JdbcLocalProjectOperationRepository operations,
            UUID tenant, UUID agent, Instant now) {
        var root = new LocalProjectRoot(UUID.randomUUID(), tenant, "Owner", "local-node", encrypted("F:/synthetic/rollback"), "c".repeat(64), now);
        var project = new LocalProject(UUID.randomUUID(), tenant, "Owner", "回滚项目", root.nodeId(), root.id(), root.identity(),
                LocalProject.State.ACTIVE, 1, now, now);
        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "回滚会话", "Owner", now, now);
        new JdbcConversationRepository(jdbc).save(tenant, conversation);
        var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "合成回滚", now);
        new JdbcRunRepository(jdbc).save(tenant, run);
        var first = new LocalProjectBinding(tenant, "Owner", project.id(), agent, conversation.id(), null,
                root.id(), root.identity(), root.nodeId(), 1, now);
        var second = new LocalProjectBinding(tenant, "Owner", project.id(), agent, conversation.id(), run.id(),
                root.id(), root.identity(), root.nodeId(), 1, now);
        var operation = new LocalProjectOperation(UUID.randomUUID(), tenant, "Owner", project.id(), agent, conversation.id(), run.id(),
                LocalProjectOperation.Kind.FILE_CHANGE, "d".repeat(64), encrypted("合成文件变更清单"),
                LocalProjectOperation.State.PROPOSED, 1, now, now);
        assertThatThrownBy(() -> {
            tx.execute(status -> {
                assertThat(rows.insertRoot(root)).isTrue();
                assertThat(rows.insert(project)).isTrue();
                assertThat(bindings.insertConversation(first)).isTrue();
                assertThat(bindings.insertRun(second)).isTrue();
                assertThat(operations.insertOperation(operation)).isTrue();
                throw new IllegalStateException("合成最终审计失败");
            });
        }).isInstanceOf(IllegalStateException.class).hasMessage("合成最终审计失败");
        assertThat(rows.find(tenant, "Owner", project.id())).isEmpty();
        assertThat(rows.root(tenant, "Owner", root.id())).isEmpty();
        assertThat(bindings.conversation(tenant, "Owner", conversation.id())).isEmpty();
        assertThat(bindings.run(tenant, "Owner", run.id())).isEmpty();
        assertThat(operations.operation(tenant, "Owner", operation.id())).isEmpty();
        assertThat(new JdbcConversationRepository(jdbc).workspaceProtocol(tenant, agent, conversation.id())).isZero();
        assertThat(new JdbcRunRepository(jdbc).workspaceProtocol(tenant, agent, run.id())).isZero();
    }

    private static void seed(JdbcClient jdbc, UUID tenant, UUID model, UUID agent, Instant now) {
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成本机项目租户',true,:now)")
                .param("id", tenant.toString()).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at)
                VALUES(:id,:tenant,'OPENAI_COMPATIBLE','合成模型','https://example.invalid','fixture','not-configured',true,:now)
                """).param("id", model.toString()).param("tenant", tenant.toString()).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO agent_definitions(id,tenant_id,name,description,system_prompt,model_provider_id,model_name,temperature,
                  max_iterations,enabled,tool_ids_json,created_by,updated_by,created_at,updated_at)
                VALUES(:id,:tenant,'合成本机项目Agent','','合成提示',:model,'fixture',0,4,true,'[]','Owner','Owner',:now,:now)
                """).param("id", agent.toString()).param("tenant", tenant.toString()).param("model", model.toString())
                .param("now", Timestamp.from(now)).update();
    }

    /** 每次只用内存随机密钥生成真实GCM信封；只验证持久化，生产密钥与上下文绑定由宿主服务负责。 */
    private static String encrypted(String text) {
        try {
            var generator = javax.crypto.KeyGenerator.getInstance("AES");
            generator.init(256);
            var key = generator.generateKey();
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            return "v1:" + java.util.Base64.getEncoder().encodeToString(iv) + ":"
                    + java.util.Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("合成加密不可用"); }
    }

    private static void probe(JdbcDatabaseContainer<?> database, UUID tenant, UUID project, UUID root, UUID operation) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        var environment = builder.environment();
        environment.put("CM_PROJECT_PROBE_URL", database.getJdbcUrl());
        environment.put("CM_PROJECT_PROBE_USER", database.getUsername());
        environment.put("CM_PROJECT_PROBE_PASSWORD", database.getPassword());
        environment.put("CM_PROJECT_PROBE_TENANT", tenant.toString());
        environment.put("CM_PROJECT_PROBE_ID", project.toString());
        environment.put("CM_PROJECT_PROBE_ROOT", root.toString());
        environment.put("CM_PROJECT_PROBE_OP", operation.toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** 独立JVM只从合成测试环境取连接信息，不输出数据库凭据或原始异常。 */
    public static final class Probe {
        /** @param args 无命令行参数，避免将测试连接凭据写入进程参数 */
        public static void main(String[] args) {
            try {
                var data = new DriverManagerDataSource(System.getenv("CM_PROJECT_PROBE_URL"),
                        System.getenv("CM_PROJECT_PROBE_USER"), System.getenv("CM_PROJECT_PROBE_PASSWORD"));
                var jdbc = JdbcClient.create(data);
                var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
                var rows = new JdbcLocalProjectRepository(jdbc, tx);
                UUID tenant = UUID.fromString(System.getenv("CM_PROJECT_PROBE_TENANT"));
                var project = rows.find(tenant, "Owner", UUID.fromString(System.getenv("CM_PROJECT_PROBE_ID"))).orElseThrow();
                if (project.state() != LocalProject.State.ARCHIVED || project.version() != 3) throw new IllegalStateException();
                if (rows.root(tenant, "Owner", UUID.fromString(System.getenv("CM_PROJECT_PROBE_ROOT"))).isEmpty()) throw new IllegalStateException();
                var operation = new JdbcLocalProjectOperationRepository(jdbc, tx)
                        .operation(tenant, "Owner", UUID.fromString(System.getenv("CM_PROJECT_PROBE_OP"))).orElseThrow();
                if (operation.state() != LocalProjectOperation.State.FAILED || operation.version() != 6) throw new IllegalStateException();
            } catch (Throwable failure) { System.exit(1); }
        }
    }
}

