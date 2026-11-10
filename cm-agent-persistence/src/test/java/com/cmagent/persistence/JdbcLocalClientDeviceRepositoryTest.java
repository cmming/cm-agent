package com.cmagent.persistence;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.domain.LocalClientPairing;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectCommand;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅在 Rocky 指定 Maven/JDK 21 容器运行，使用测试独占的 PostgreSQL 和 MySQL。 */
@Testcontainers
class JdbcLocalClientDeviceRepositoryTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void PostgreSQL设备隔离撤销和挑战单次消费() throws Exception { verify(POSTGRES); }
    @Test void MySQL设备隔离撤销和挑战单次消费() throws Exception { verify(MYSQL); }

    private void verify(JdbcDatabaseContainer<?> database) throws Exception {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        CmAgentFlyway.configure(source).load().migrate();
        var jdbc = JdbcClient.create(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var devices = new JdbcLocalClientDeviceRepository(jdbc, transaction);
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,:name,true,:created)")
                .param("id", tenant.toString()).param("code", tenant.toString()).param("name", "设备测试租户")
                .param("created", java.sql.Timestamp.from(now)).update();
        String key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair().getPublic().getEncoded());
        var device = new LocalClientDevice(UUID.randomUUID(), tenant, "Owner", "测试设备",
                LocalClientDevice.Platform.WINDOWS, key, LocalClientDevice.State.ACTIVE, 1, now, now);
        assertThat(devices.insert(device)).isTrue();
        assertThat(devices.insert(device)).isFalse();
        assertThat(devices.find(tenant, "Owner", device.id())).contains(device);
        for (String other : List.of("owner", "Owner ", "another")) {
            assertThat(devices.find(tenant, other, device.id())).isEmpty();
        }
        assertThat(devices.find(UUID.randomUUID(), "Owner", device.id())).isEmpty();

        var challenge = challenge(device, now);
        assertThat(devices.insertChallenge(challenge)).isTrue();
        assertThat(devices.insertChallenge(challenge)).isFalse();
        assertThat(devices.findChallenge(tenant, "owner", device.id(), challenge.id(), now)).isEmpty();
        assertThat(devices.findChallenge(tenant, "Owner", device.id(), challenge.id(), now)).contains(challenge);
        assertThat(devices.findChallenge(tenant, "Owner", device.id(), challenge.id(), challenge.expiresAt())).isEmpty();
        assertThat(devices.consumeChallenge(tenant, "owner", device.id(), challenge.id(), now)).isFalse();

        // 同一挑战只允许一条并发请求取得消费权。
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return devices.consumeChallenge(tenant, "Owner", device.id(), challenge.id(), now); });
            var second = pool.submit(() -> { start.await(); return devices.consumeChallenge(tenant, "Owner", device.id(), challenge.id(), now); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(devices.findChallenge(tenant, "Owner", device.id(), challenge.id(), now)).isEmpty();

        var rollback = challenge(device, now);
        assertThat(devices.insertChallenge(rollback)).isTrue();
        transaction.executeWithoutResult(status -> {
            assertThat(devices.consumeChallenge(tenant, "Owner", device.id(), rollback.id(), now)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(devices.consumeChallenge(tenant, "Owner", device.id(), rollback.id(), now)).isTrue();

        UUID model = UUID.randomUUID(), agent = UUID.randomUUID();
        seedAgent(jdbc, tenant, model, agent, now);
        verifyClientProjectCommands(jdbc, transaction, device, agent, now);
        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "设备授权会话", "Owner", now, now);
        new JdbcConversationRepository(jdbc).save(tenant, conversation);
        var run = createConversationRun(jdbc, transaction, tenant, agent, conversation.id(), now);
        var grant = grant(device, agent, conversation.id(), run.id(), now);
        var wrongConversation = grant(device, agent, UUID.randomUUID(), run.id(), now);
        assertThat(devices.insertGrant(wrongConversation)).isFalse();
        assertThat(devices.insertGrant(grant)).isTrue();
        assertThat(devices.insertGrant(grant)).isFalse();
        assertThat(devices.findActiveGrant(tenant, "Owner", run.id(), device.id(), now)).contains(grant);
        assertThat(devices.findActiveGrant(tenant, "owner", run.id(), device.id(), now)).isEmpty();
        assertThat(devices.findActiveGrant(UUID.randomUUID(), "Owner", run.id(), device.id(), now)).isEmpty();
        assertThat(devices.findActiveGrant(tenant, "Owner", run.id(), UUID.randomUUID(), now)).isEmpty();
        assertThat(devices.findActiveGrant(tenant, "Owner", run.id(), device.id(), grant.expiresAt())).isEmpty();
        transaction.executeWithoutResult(status -> {
            assertThat(devices.revokeGrant(tenant, "Owner", grant.authorizationId(), now)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(devices.findActiveGrant(tenant, "Owner", run.id(), device.id(), now)).contains(grant);
        assertThat(devices.revokeGrant(tenant, "Owner", grant.authorizationId(), now)).isTrue();
        assertThat(devices.revokeGrant(tenant, "Owner", grant.authorizationId(), now)).isFalse();
        assertThat(devices.findActiveGrant(tenant, "Owner", run.id(), device.id(), now)).isEmpty();

        var secondRun = createConversationRun(jdbc, transaction, tenant, agent, conversation.id(), now);
        var secondGrant = grant(device, agent, conversation.id(), secondRun.id(), now);
        assertThat(devices.insertGrant(secondGrant)).isTrue();

        var operation = new LocalClientOperation(UUID.randomUUID(), tenant, "Owner", device.id(), agent,
                conversation.id(), secondRun.id(), UUID.randomUUID(), UUID.randomUUID().toString(),
                OperationCategory.READ_ONLY, "ciphertext", LocalClientOperation.State.PENDING,
                "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(devices.insertOperation(operation)).isTrue();
        assertThat(devices.insertOperation(operation)).isFalse();
        var claimed = devices.claimNextOperation(tenant, "Owner", device.id(), now).orElseThrow();
        assertThat(claimed.state()).isEqualTo(LocalClientOperation.State.DISPATCHED);
        assertThat(devices.claimNextOperation(tenant, "Owner", device.id(), now)).isEmpty();
        assertThat(devices.completeOperation(claimed.id(), tenant, "Owner", device.id(),
                LocalClientOperation.State.SUCCEEDED, "result-cipher", "", claimed.version(), now)).isTrue();
        assertThat(devices.completeOperation(claimed.id(), tenant, "Owner", device.id(),
                LocalClientOperation.State.FAILED, "", "重复", claimed.version(), now)).isFalse();

        var thirdRun = createConversationRun(jdbc, transaction, tenant, agent, conversation.id(), now);
        var thirdGrant = grant(device, agent, conversation.id(), thirdRun.id(), now);
        assertThat(devices.insertGrant(thirdGrant)).isTrue();
        var pendingOperation = new LocalClientOperation(UUID.randomUUID(), tenant, "Owner", device.id(), agent,
                conversation.id(), thirdRun.id(), UUID.randomUUID(), UUID.randomUUID().toString(),
                OperationCategory.READ_ONLY, "ciphertext", LocalClientOperation.State.PENDING,
                "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(devices.insertOperation(pendingOperation)).isTrue();
        // 严格审计与领取共用外层事务，审计异常回滚后任务仍未派发。
        assertThatThrownBy(() -> transaction.execute(status -> {
            assertThat(devices.claimNextOperation(tenant, "Owner", device.id(), now)).isPresent();
            throw new IllegalStateException("模拟审计失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(devices.findOperation(tenant, "Owner", device.id(), pendingOperation.id()).orElseThrow().state())
                .isEqualTo(LocalClientOperation.State.PENDING);
        var revokeHeld = new CountDownLatch(1);
        var releaseRevoke = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revokeGrant = pool.submit(() -> transaction.execute(status -> {
                boolean changed = devices.revokeGrant(tenant, "Owner", thirdGrant.authorizationId(), now);
                revokeHeld.countDown();
                try {
                    if (!releaseRevoke.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放事务");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试等待被中断", interrupted);
                }
                return changed;
            }));
            try {
                assertThat(revokeHeld.await(10, TimeUnit.SECONDS)).isTrue();
                var claimStarted = new CountDownLatch(1);
                var competingClaim = pool.submit(() -> {
                    claimStarted.countDown();
                    return devices.claimNextOperation(tenant, "Owner", device.id(), now);
                });
                assertThat(claimStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> competingClaim.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                releaseRevoke.countDown();
                assertThat(revokeGrant.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(competingClaim.get(10, TimeUnit.SECONDS)).isEmpty();
            } finally {
                releaseRevoke.countDown();
            }
        }

        var pairingId = UUID.randomUUID();
        var pendingPairing = new LocalClientPairing(pairingId, tenant, "Owner", "新设备",
                LocalClientDevice.Platform.LINUX, key, new byte[32], new byte[64],
                LocalClientPairing.State.PENDING, null, now, now.plusSeconds(60), null);
        assertThat(devices.insertPairing(pendingPairing)).isTrue();
        assertThat(devices.findPairing(pairingId)).contains(pendingPairing);
        var pairedDevice = new LocalClientDevice(UUID.randomUUID(), tenant, "Owner", "新设备",
                LocalClientDevice.Platform.LINUX, key, LocalClientDevice.State.ACTIVE, 1, now, now);
        assertThat(devices.confirmPairing(tenant, "Owner", pairingId, pairedDevice, now)).isTrue();
        assertThat(devices.findAnyById(pairedDevice.id())).contains(pairedDevice);
        assertThat(devices.confirmPairing(tenant, "Owner", pairingId, pairedDevice, now)).isFalse();

        var pending = challenge(device, now);
        assertThat(devices.insertChallenge(pending)).isTrue();
        assertThat(devices.revoke(tenant, "owner", device.id(), 1, now)).isFalse();
        assertThat(devices.revoke(tenant, "Owner", device.id(), 1, now)).isTrue();
        assertThat(devices.revoke(tenant, "Owner", device.id(), 1, now)).isFalse();
        assertThat(devices.findChallenge(tenant, "Owner", device.id(), pending.id(), now)).isEmpty();
        assertThat(devices.consumeChallenge(tenant, "Owner", device.id(), pending.id(), now)).isFalse();
        assertThat(devices.insertChallenge(challenge(device, now))).isFalse();
        assertThat(devices.findActiveGrant(tenant, "Owner", secondRun.id(), device.id(), now)).isEmpty();

        var competingDevice = new LocalClientDevice(UUID.randomUUID(), tenant, "Owner", "竞争测试设备",
                LocalClientDevice.Platform.LINUX, key, LocalClientDevice.State.ACTIVE, 1, now, now);
        assertThat(devices.insert(competingDevice)).isTrue();
        var competingChallenge = challenge(competingDevice, now);
        assertThat(devices.insertChallenge(competingChallenge)).isTrue();
        var revokedInsideTransaction = new CountDownLatch(1);
        var releaseTransaction = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> transaction.execute(status -> {
                boolean changed = devices.revoke(tenant, "Owner", competingDevice.id(), 1, now);
                revokedInsideTransaction.countDown();
                try {
                    if (!releaseTransaction.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放事务");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试等待被中断", interrupted);
                }
                return changed;
            }));
            try {
                assertThat(revokedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
                var consumeStarted = new CountDownLatch(1);
                var consume = pool.submit(() -> {
                    consumeStarted.countDown();
                    return devices.consumeChallenge(tenant, "Owner", competingDevice.id(), competingChallenge.id(), now);
                });
                assertThat(consumeStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> consume.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                releaseTransaction.countDown();
                assertThat(revoke.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(consume.get(10, TimeUnit.SECONDS)).isFalse();
            } finally {
                releaseTransaction.countDown();
            }
        }
    }

    /** 复用测试独占数据库验证协议 5 的首次来源、任务唯一领取、命令 CAS 与未知状态隔离。 */
    private void verifyClientProjectCommands(JdbcClient jdbc, TransactionTemplate transaction,
            LocalClientDevice device, UUID agent, Instant now) throws Exception {
        UUID tenant = device.tenantId(), projectId = UUID.randomUUID(), rootId = UUID.randomUUID();
        var projects = new JdbcClientProjectRepository(jdbc, transaction);
        var bindings = new JdbcClientProjectBindingRepository(jdbc, transaction);
        var commands = new JdbcClientProjectCommandRepository(jdbc, transaction);
        var tasks = new JdbcClientProjectTaskRepository(jdbc, transaction);
        var project = new ClientProject(projectId, tenant, "Owner", device.id(), rootId,
                "a".repeat(64), "命令验收项目", ClientProject.State.ACTIVE, 1, now, now);
        assertThat(projects.insert(project)).isTrue();
        assertThat(projects.find(tenant, "owner", projectId)).isEmpty();
        assertThat(projects.find(UUID.randomUUID(), "Owner", projectId)).isEmpty();
        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "设备项目", "Owner", now, now);
        var conversationRows = new JdbcConversationRepository(jdbc);
        conversationRows.save(tenant, conversation);
        assertThat(bindings.insertConversation(new ClientProjectBinding(tenant, "Owner", projectId,
                agent, conversation.id(), null, device.id(), rootId, project.rootIdentity(), 1, now))).isTrue();
        assertThat(conversationRows.workspaceProtocol(tenant, agent, conversation.id())).isEqualTo(5);
        assertThat(bindings.conversation(tenant, "owner", conversation.id())).isEmpty();
        var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "命令验收", now);
        var runRows = new JdbcRunRepository(jdbc);
        runRows.saveWithWorkspaceProtocol(tenant, run, 5);
        new JdbcConversationMessageRepository(jdbc, new ObjectMapper(), transaction).append(tenant,
                new ConversationMessageDraft(UUID.randomUUID(), tenant, conversation.id(), MessageRole.USER,
                        "测试", List.of(MessageContentBlock.text("命令验收")), run.id(), now));
        assertThat(bindings.insertRun(new ClientProjectBinding(tenant, "Owner", projectId, agent,
                conversation.id(), run.id(), device.id(), rootId, project.rootIdentity(), 1, now))).isTrue();
        assertThat(runRows.workspaceProtocol(tenant, agent, run.id())).isEqualTo(5);
        assertThat(bindings.run(tenant, "owner", run.id())).isEmpty();
        var proposed = new ClientProjectCommand(UUID.randomUUID(), tenant, "Owner", projectId, agent,
                conversation.id(), run.id(), "b".repeat(64), "c".repeat(64), true,
                "合成加密清单", "", false, false, null, ClientProjectCommand.State.PROPOSED,
                false, 0, 1, now, now);
        assertThat(commands.insert(proposed)).isTrue();
        assertThat(commands.find(tenant, "Owner", proposed.id()).orElseThrow().exitCode()).isNull();
        assertThat(commands.find(tenant, "owner", proposed.id())).isEmpty();
        assertThat(commands.find(UUID.randomUUID(), "Owner", proposed.id())).isEmpty();
        var queued = proposed.update(ClientProjectCommand.State.QUEUED, false, null, false, false, "", 0, now);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return commands.update(tenant, "Owner", proposed.id(), 1, queued).isPresent(); });
            var second = pool.submit(() -> { start.await(); return commands.update(tenant, "Owner", proposed.id(), 1, queued).isPresent(); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var task = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, proposed.id(), ClientProjectTask.Kind.RUN_COMMAND,
                "合成加密请求", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(tasks.insert(task)).isTrue();
        assertThat(tasks.claimNext(tenant, "owner", device.id(), now)).isEmpty();
        assertThat(tasks.claimNext(UUID.randomUUID(), "Owner", device.id(), now)).isEmpty();
        var claimed = tasks.claimNext(tenant, "Owner", device.id(), now).orElseThrow();
        assertThat(claimed.commandId()).isEqualTo(proposed.id());
        assertThat(tasks.claimNext(tenant, "Owner", device.id(), now)).isEmpty();
        assertThat(tasks.complete(tenant, "Owner", device.id(), task.id(), 1,
                ClientProjectTask.State.SUCCEEDED, "合成加密结果", "", now)).isFalse();
        assertThat(tasks.complete(tenant, "Owner", device.id(), task.id(), claimed.version(),
                ClientProjectTask.State.SUCCEEDED, "合成加密结果", "", now)).isTrue();
        assertThat(tasks.complete(tenant, "Owner", device.id(), task.id(), claimed.version(),
                ClientProjectTask.State.SUCCEEDED, "合成加密结果", "", now)).isFalse();
        var running = queued.update(ClientProjectCommand.State.RUNNING, false, null, false, false, "", 0, now);
        assertThat(commands.update(tenant, "Owner", proposed.id(), queued.version(), running)).contains(running);
        assertThat(commands.find(tenant, "Owner", proposed.id())).contains(running);
        var stopped = running.update(ClientProjectCommand.State.STOP_REQUESTED, true, null, false, false, "", 0, now);
        assertThat(commands.update(tenant, "Owner", proposed.id(), running.version(), stopped)).contains(stopped);
        assertThat(commands.expireUnsettled(tenant, "Owner", run.id(), now.plusSeconds(1), now.plusSeconds(2))).isEqualTo(1);
        var unknown = commands.find(tenant, "Owner", proposed.id()).orElseThrow();
        assertThat(unknown.state()).isEqualTo(ClientProjectCommand.State.UNKNOWN);
        assertThat(commands.hasUnsettledForProject(tenant, "Owner", projectId)).isTrue();
        assertThat(commands.listUnsettledForRun(tenant, "owner", run.id())).isEmpty();
        var completed = unknown.update(ClientProjectCommand.State.SUCCEEDED, true, 0, false, true,
                "合成加密输出", 1, now.plusSeconds(3));
        assertThat(commands.update(tenant, "Owner", proposed.id(), unknown.version(), completed)).contains(completed);
        assertThat(commands.find(tenant, "Owner", proposed.id())).contains(completed);
        assertThat(commands.hasUnsettledForProject(tenant, "Owner", projectId)).isFalse();
    }

    private static LocalClientChallenge challenge(LocalClientDevice device, Instant now) {
        return new LocalClientChallenge(UUID.randomUUID(), device.tenantId(), device.ownerId(), device.id(),
                new byte[32], now, now.plusSeconds(30));
    }

    private static OperationAuthorizationSnapshot grant(LocalClientDevice device, UUID agent,
                                                         UUID conversation, UUID run, Instant now) {
        return new OperationAuthorizationSnapshot(UUID.randomUUID(), device.tenantId(), device.ownerId(), agent,
                conversation, run, device.id(), OperationAuthorizationMode.FULL_ACCESS,
                Set.of(OperationCategory.READ_ONLY, OperationCategory.COMMAND), 1, now, now.plusSeconds(60));
    }

    private static RunRecord createConversationRun(JdbcClient jdbc, TransactionTemplate transaction, UUID tenant,
                                                    UUID agent, UUID conversation, Instant now) {
        var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "测试输入", now);
        new JdbcRunRepository(jdbc).save(tenant, run);
        new JdbcConversationMessageRepository(jdbc, new ObjectMapper(), transaction).append(tenant,
                new ConversationMessageDraft(UUID.randomUUID(), tenant, conversation, MessageRole.USER,
                        "Owner", List.of(MessageContentBlock.text("测试输入")), run.id(), now));
        return run;
    }

    private static void seedAgent(JdbcClient jdbc, UUID tenant, UUID model, UUID agent, Instant now) {
        var timestamp = java.sql.Timestamp.from(now);
        jdbc.sql("""
                INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at)
                VALUES(:id,:tenant,'OPENAI_COMPATIBLE','合成模型','https://example.invalid','fixture','not-configured',true,:now)
                """).param("id", model.toString()).param("tenant", tenant.toString()).param("now", timestamp).update();
        jdbc.sql("""
                INSERT INTO agent_definitions(id,tenant_id,name,description,system_prompt,model_provider_id,model_name,temperature,
                  max_iterations,enabled,tool_ids_json,created_by,updated_by,created_at,updated_at)
                VALUES(:id,:tenant,'合成智能体','','合成提示',:model,'fixture',0,4,true,'[]','Owner','Owner',:now,:now)
                """).param("id", agent.toString()).param("tenant", tenant.toString()).param("model", model.toString())
                .param("now", timestamp).update();
    }
}
