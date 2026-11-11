package com.cmagent.persistence;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.domain.LocalClientPairing;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.ClientProjectCommand;
import com.cmagent.core.domain.ClientProjectOperation;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
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

    private static javax.sql.DataSource dataSource(TransactionTemplate transaction) {
        return java.util.Objects.requireNonNull(
                ((DataSourceTransactionManager) transaction.getTransactionManager()).getDataSource());
    }

    private static boolean insertFenced(TransactionTemplate transaction,
            JdbcClientProjectFencedTaskWriter writer, ClientProjectTask task, UUID instance, long epoch) {
        return Boolean.TRUE.equals(transaction.execute(status -> writer.insert(task, instance, epoch)));
    }

    private void verify(JdbcDatabaseContainer<?> database) throws Exception {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        CmAgentFlyway.configure(source).load().migrate();
        var jdbc = JdbcClient.create(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        // 相同连接参数也不能证明两个数据源共享事务资源，必须拒绝错误装配。
        var otherSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        assertThatThrownBy(() -> new JdbcClientProjectRunOwnershipRepository(otherSource, transaction))
                .isInstanceOf(IllegalArgumentException.class);
        var ownershipGuard = new JdbcClientProjectRunOwnershipRepository(source, transaction);
        var otherTransaction = new TransactionTemplate(new DataSourceTransactionManager(otherSource));
        otherTransaction.executeWithoutResult(status -> {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            assertThatThrownBy(() -> ownershipGuard.lockExecution(UUID.randomUUID(), "Owner",
                    UUID.randomUUID(), UUID.randomUUID(), 1))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        });
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
        var runBinding = new ClientProjectBinding(tenant, "Owner", projectId, agent,
                conversation.id(), run.id(), device.id(), rootId, project.rootIdentity(), 1, now);
        UUID instance = UUID.randomUUID();
        var ownership = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction);
        var firstOwnership = transaction.execute(status -> {
            runRows.saveWithWorkspaceProtocol(tenant, run, 5);
            new JdbcConversationMessageRepository(jdbc, new ObjectMapper(), transaction).append(tenant,
                    new ConversationMessageDraft(UUID.randomUUID(), tenant, conversation.id(), MessageRole.USER,
                            "测试", List.of(MessageContentBlock.text("命令验收")), run.id(), now));
            assertThat(bindings.insertRun(runBinding)).isTrue();
            return ownership.insertInitial(runBinding, instance, Duration.ofSeconds(5)).orElseThrow();
        });
        var fencedWriter = new JdbcClientProjectFencedTaskWriter(ownership);
        var fencedTask = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThatThrownBy(() -> fencedWriter.insert(fencedTask, instance, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(insertFenced(transaction, fencedWriter, fencedTask, UUID.randomUUID(), 1)).isFalse();
        assertThat(insertFenced(transaction, fencedWriter, fencedTask, instance, 2)).isFalse();
        assertThat(tasks.findForOwner(tenant, "Owner", fencedTask.id())).isEmpty();
        var otherOwnerTask = new ClientProjectTask(UUID.randomUUID(), tenant, "owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        var otherTenantTask = new ClientProjectTask(UUID.randomUUID(), UUID.randomUUID(), "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(insertFenced(transaction, fencedWriter, otherOwnerTask, instance, 1)).isFalse();
        assertThat(insertFenced(transaction, fencedWriter, otherTenantTask, instance, 1)).isFalse();
        var mismatchedTask = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), UUID.randomUUID(),
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(insertFenced(transaction, fencedWriter, mismatchedTask, instance, 1)).isFalse();
        transaction.executeWithoutResult(status -> {
            assertThat(fencedWriter.insert(fencedTask, instance, 1)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(tasks.findForOwner(tenant, "Owner", fencedTask.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM client_project_task_fences WHERE tenant_id=:tenant AND task_id=:task")
                .param("tenant", tenant.toString()).param("task", fencedTask.id().toString())
                .query(Long.class).single()).isZero();
        assertThat(insertFenced(transaction, fencedWriter, fencedTask, instance, 1)).isTrue();
        assertThat(jdbc.sql("SELECT epoch FROM client_project_task_fences WHERE tenant_id=:tenant AND task_id=:task")
                .param("tenant", tenant.toString()).param("task", fencedTask.id().toString())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(tasks.claimNext(tenant, "Owner", device.id(), now)).isEmpty();
        assertThat(tasks.claimNext(tenant, "Owner", device.id(), now.plusSeconds(61))).isEmpty();
        assertThat(tasks.findForOwner(tenant, "Owner", fencedTask.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.PENDING);
        assertThat(tasks.close(tenant, "Owner", device.id(), fencedTask.id(), 1,
                ClientProjectTask.State.CANCELLED, "测试关闭", now)).isFalse();
        verifyRunOwnership(jdbc, transaction, runBinding, instance, firstOwnership);
        assertThat(tasks.findForOwner(tenant, "Owner", fencedTask.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.CANCELLED);
        // 升级前已单独提交的 RUNNING 来源没有创建事务见证，不能补造首代执行权。
        var legacyRun = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "历史运行", now);
        runRows.saveWithWorkspaceProtocol(tenant, legacyRun, 5);
        var legacyBinding = new ClientProjectBinding(tenant, "Owner", projectId, agent,
                conversation.id(), legacyRun.id(), device.id(), rootId, project.rootIdentity(), 1, now);
        assertThat(bindings.insertRun(legacyBinding)).isTrue();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status ->
                ownership.insertInitial(legacyBinding, UUID.randomUUID(), Duration.ofSeconds(5)))).isEmpty();
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
        verifyRecoveryIsolation(jdbc, transaction, device, agent, now);
    }

    /** 验证隔离与审计回调失败一起回滚，成功时保留未知副作用。 */
    private void verifyRecoveryIsolation(JdbcClient jdbc, TransactionTemplate transaction,
            LocalClientDevice device, UUID agent, Instant now) throws Exception {
        UUID tenant = device.tenantId(), projectId = UUID.randomUUID(), rootId = UUID.randomUUID();
        var projects = new JdbcClientProjectRepository(jdbc, transaction);
        var bindings = new JdbcClientProjectBindingRepository(jdbc, transaction);
        var commands = new JdbcClientProjectCommandRepository(jdbc, transaction);
        var operations = new JdbcClientProjectOperationRepository(jdbc, transaction);
        var tasks = new JdbcClientProjectTaskRepository(jdbc, transaction);
        var ownership = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction);
        UUID instance = UUID.randomUUID();
        var productionTasks = new JdbcClientProjectTaskRepository(jdbc, transaction, ownership, instance);
        var recoveryRepository = new JdbcClientProjectRunRecoveryRepository(jdbc, transaction);
        var isolation = new JdbcClientProjectRunIsolation(ownership);
        var project = new ClientProject(projectId, tenant, "Owner", device.id(), rootId,
                "d".repeat(64), "恢复隔离项目", ClientProject.State.ACTIVE, 1, now, now);
        assertThat(projects.insert(project)).isTrue();
        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "恢复隔离", "Owner", now, now);
        new JdbcConversationRepository(jdbc).save(tenant, conversation);
        assertThat(bindings.insertConversation(new ClientProjectBinding(tenant, "Owner", projectId,
                agent, conversation.id(), null, device.id(), rootId, project.rootIdentity(), 1, now))).isTrue();
        var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "恢复隔离", now);
        var binding = new ClientProjectBinding(tenant, "Owner", projectId, agent, conversation.id(),
                run.id(), device.id(), rootId, project.rootIdentity(), 1, now);
        transaction.executeWithoutResult(status -> {
            new JdbcRunRepository(jdbc).saveWithWorkspaceProtocol(tenant, run, 5);
            assertThat(bindings.insertRun(binding)).isTrue();
            assertThat(ownership.insertInitial(binding, instance, Duration.ofSeconds(8))).isPresent();
        });
        var acknowledgements = new JdbcClientProjectFenceAcknowledgements(ownership);
        var reader = new JdbcClientProjectFencedTaskReader(ownership);
        var fencedDispatched = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(insertFenced(transaction, new JdbcClientProjectFencedTaskWriter(ownership),
                fencedDispatched, instance, 1)).isTrue();
        assertThatThrownBy(() -> reader.claim(device)).isInstanceOf(IllegalStateException.class);
        assertThat(transaction.<Optional<JdbcClientProjectFencedTaskReader.Claim>>execute(status -> reader.claim(device)))
                .isEmpty();
        assertThatThrownBy(() -> acknowledgements.lockConfirmed(device, run.id(), instance, 1,
                ClientProjectRunOwnership.Phase.EXECUTING)).isInstanceOf(IllegalStateException.class);
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(), instance, 1,
                ClientProjectRunOwnership.Phase.EXECUTING))).isFalse();
        var wrongOwner = new LocalClientDevice(device.id(), tenant, "owner", device.displayName(),
                device.platform(), device.publicKey(), device.state(), device.version(), device.createdAt(), device.updatedAt());
        var wrongDevice = new LocalClientDevice(UUID.randomUUID(), tenant, "Owner", device.displayName(),
                device.platform(), device.publicKey(), device.state(), device.version(), device.createdAt(), device.updatedAt());
        var wrongTenant = new LocalClientDevice(device.id(), UUID.randomUUID(), "Owner", device.displayName(),
                device.platform(), device.publicKey(), device.state(), device.version(), device.createdAt(), device.updatedAt());
        assertThat(acknowledgements.current(wrongOwner, run.id())).isEmpty();
        assertThat(acknowledgements.current(wrongDevice, run.id())).isEmpty();
        assertThat(acknowledgements.current(wrongTenant, run.id())).isEmpty();
        // 同一设备可能仍有前序运行待确认；此处只要求设备存在至少一个未确认代次。
        assertThat(acknowledgements.awaitingCurrent(device)).isPresent();
        var currentFence = acknowledgements.current(device, run.id()).orElseThrow();
        assertThat(currentFence.runId()).isEqualTo(run.id());
        assertThat(currentFence.rootId()).isEqualTo(rootId);
        assertThat(currentFence.rootIdentity()).isEqualTo(project.rootIdentity());
        assertThat(currentFence.epoch()).isEqualTo(1);
        assertThat(currentFence.phase()).isEqualTo(ClientProjectRunOwnership.Phase.EXECUTING);
        assertThat(currentFence.acknowledged()).isFalse();
        assertThat(acknowledgements.acknowledge(wrongOwner, run.id(), 1)).isFalse();
        assertThat(acknowledgements.acknowledge(wrongDevice, run.id(), 1)).isFalse();
        assertThat(acknowledgements.acknowledge(wrongTenant, run.id(), 1)).isFalse();
        assertThat(acknowledgements.acknowledge(device, run.id(), 2)).isFalse();
        transaction.executeWithoutResult(status -> {
            assertThat(acknowledgements.acknowledge(device, run.id(), 1)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM client_project_run_fence_acknowledgements"
                + " WHERE tenant_id=:tenant AND run_id=:run").param("tenant", tenant.toString())
                .param("run", run.id().toString()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE tenant_id=:tenant"
                + " AND resource_id=:run AND event_type='CLIENT_PROJECT_FENCE_ACK'")
                .param("tenant", tenant.toString()).param("run", run.id().toString())
                .query(Long.class).single()).isZero();
        assertThat(acknowledgements.acknowledge(device, run.id(), 1)).isTrue();
        assertThat(acknowledgements.acknowledge(device, run.id(), 1)).isTrue();
        assertThat(acknowledgements.awaitingCurrent(device).map(current -> current.runId()).orElse(null))
                .isNotEqualTo(run.id());
        assertThat(acknowledgements.current(device, run.id()).orElseThrow().acknowledged()).isTrue();
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(), instance, 1,
                ClientProjectRunOwnership.Phase.EXECUTING))).isTrue();
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(), UUID.randomUUID(), 1,
                ClientProjectRunOwnership.Phase.EXECUTING))).isFalse();
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(), instance, 1,
                ClientProjectRunOwnership.Phase.RECOVERING))).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM client_project_run_fence_acknowledgements"
                + " WHERE tenant_id=:tenant AND run_id=:run").param("tenant", tenant.toString())
                .param("run", run.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE tenant_id=:tenant"
                + " AND resource_id=:run AND event_type='CLIENT_PROJECT_FENCE_ACK'")
                .param("tenant", tenant.toString()).param("run", run.id().toString())
                .query(Long.class).single()).isEqualTo(1);
        var unrelatedProject = new ClientProject(UUID.randomUUID(), tenant, "Owner", device.id(), UUID.randomUUID(),
                "e".repeat(64), "来源核对项目", ClientProject.State.ACTIVE, 1, now, now);
        assertThat(projects.insert(unrelatedProject)).isTrue();
        // 模拟任务来源被错误修改；单靠任务代次相同不能允许跨项目派发。
        jdbc.sql("UPDATE client_project_tasks SET project_id=:project WHERE tenant_id=:tenant AND id=:task")
                .param("project", unrelatedProject.id().toString()).param("tenant", tenant.toString())
                .param("task", fencedDispatched.id().toString()).update();
        assertThat(transaction.<Optional<JdbcClientProjectFencedTaskReader.Claim>>execute(status -> reader.claim(device)))
                .isEmpty();
        jdbc.sql("UPDATE client_project_tasks SET project_id=:project WHERE tenant_id=:tenant AND id=:task")
                .param("project", projectId.toString()).param("tenant", tenant.toString())
                .param("task", fencedDispatched.id().toString()).update();
        transaction.executeWithoutResult(status -> {
            assertThat(reader.claim(device)).isPresent();
            status.setRollbackOnly();
        });
        assertThat(tasks.findForOwner(tenant, "Owner", fencedDispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.PENDING);
        var claimStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { claimStart.await(); return transaction.execute(status -> reader.claim(device)); });
            var second = pool.submit(() -> { claimStart.await(); return transaction.execute(status -> reader.claim(device)); });
            claimStart.countDown();
            var claims = List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
            var claim = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(claim.task().id()).isEqualTo(fencedDispatched.id());
            assertThat(claim.epoch()).isEqualTo(1);
            assertThat(claim.instanceId()).isEqualTo(instance);
        }
        var fencedPending = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(insertFenced(transaction, new JdbcClientProjectFencedTaskWriter(ownership),
                fencedPending, instance, 1)).isTrue();
        // 新领取入口已条件派发；旧回报入口不携带代次，不能推进该任务。
        assertThat(tasks.complete(tenant, "Owner", device.id(), fencedDispatched.id(), 2,
                ClientProjectTask.State.SUCCEEDED, "密文", "", now)).isFalse();
        assertThat(tasks.close(tenant, "Owner", device.id(), fencedDispatched.id(), 2,
                ClientProjectTask.State.UNKNOWN, "测试", now)).isFalse();
        assertThatThrownBy(() -> reader.complete(device, fencedDispatched.id(), 2,
                ClientProjectTask.State.SUCCEEDED, "密文", "")).isInstanceOf(IllegalStateException.class);
        assertThat(transaction.<Boolean>execute(status -> reader.complete(wrongOwner, fencedDispatched.id(), 2,
                ClientProjectTask.State.SUCCEEDED, "密文", ""))).isFalse();
        assertThat(transaction.<Boolean>execute(status -> reader.complete(device, fencedDispatched.id(), 3,
                ClientProjectTask.State.SUCCEEDED, "密文", ""))).isFalse();
        transaction.executeWithoutResult(status -> {
            assertThat(productionTasks.completeForDevice(device, fencedDispatched.id(), 2,
                    ClientProjectTask.State.SUCCEEDED, "密文", "", now)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(tasks.findForOwner(tenant, "Owner", fencedDispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.DISPATCHED);
        var proposed = new ClientProjectCommand(UUID.randomUUID(), tenant, "Owner", projectId, agent,
                conversation.id(), run.id(), "b".repeat(64), "c".repeat(64), true, "密文", "",
                false, false, null, ClientProjectCommand.State.PROPOSED, false, 0, 1, now, now);
        assertThat(commands.insert(proposed)).isTrue();
        var queued = proposed.update(ClientProjectCommand.State.QUEUED, false, null, false, false, "", 0, now);
        assertThat(commands.update(tenant, "Owner", proposed.id(), 1, queued)).isPresent();
        var running = queued.update(ClientProjectCommand.State.RUNNING, false, null, false, false, "", 0, now);
        assertThat(commands.update(tenant, "Owner", proposed.id(), 2, running)).isPresent();
        var operation = new ClientProjectOperation(UUID.randomUUID(), tenant, "Owner", projectId, agent,
                conversation.id(), run.id(), null, "a".repeat(64), "b".repeat(64), true, "密文",
                ClientProjectOperation.State.PROPOSED, 1, now, now);
        assertThat(operations.insert(operation)).isTrue();
        assertThat(operations.advance(tenant, "Owner", operation.id(), 1,
                ClientProjectOperation.State.APPLYING, now)).isPresent();
        var dispatched = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, proposed.id(), ClientProjectTask.Kind.RUN_COMMAND,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(tasks.insert(dispatched)).isTrue();
        assertThat(tasks.claimNext(tenant, "Owner", device.id(), now)).isPresent();
        var pending = new ClientProjectTask(UUID.randomUUID(), tenant, "Owner", device.id(), projectId,
                run.id(), agent, conversation.id(), null, null, ClientProjectTask.Kind.PLAN_CHANGE,
                "密文", ClientProjectTask.State.PENDING, "", "", now, now.plusSeconds(60), null, null, 1);
        assertThat(tasks.insert(pending)).isTrue();
        var audit = new JdbcAuditEventRepository(jdbc, transaction);
        UUID auditId = UUID.randomUUID();
        var auditEvent = new AuditEvent(auditId, tenant, "Owner", "CLIENT_PROJECT_RUN_RECOVERY",
                "CLIENT_PROJECT_RUN", run.id().toString(), "RECOVERING", "隔离验证", now);
        audit.append(auditEvent);
        Thread.sleep(8300);
        assertThat(recoveryRepository.findExpired(10))
                .anySatisfy(candidate -> assertThat(candidate.runId()).isEqualTo(run.id()));
        var failingBarrier = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction,
                (previous, next) -> {
                    isolation.quarantine(previous, next);
                    throw new IllegalStateException("合成审计失败");
                });
        assertThatThrownBy(() -> failingBarrier.takeOverForRecovery(tenant, "Owner", run.id(), 1,
                UUID.randomUUID(), Duration.ofSeconds(20))).isInstanceOf(IllegalStateException.class);
        assertThat(commands.find(tenant, "Owner", proposed.id()).orElseThrow().state())
                .isEqualTo(ClientProjectCommand.State.RUNNING);
        assertThat(operations.find(tenant, "Owner", operation.id()).orElseThrow().state())
                .isEqualTo(ClientProjectOperation.State.APPLYING);
        assertThat(tasks.findForOwner(tenant, "Owner", dispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.DISPATCHED);
        assertThat(tasks.findForOwner(tenant, "Owner", fencedPending.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.PENDING);
        // 审计唯一键冲突由真实 JDBC 抛出，验证它与隔离写入处于同一事务。
        var failingAudit = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction,
                (previous, next) -> {
                    isolation.quarantine(previous, next);
                    audit.append(auditEvent);
                });
        assertThatThrownBy(() -> failingAudit.takeOverForRecovery(tenant, "Owner", run.id(), 1,
                UUID.randomUUID(), Duration.ofSeconds(20))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(commands.find(tenant, "Owner", proposed.id()).orElseThrow().state())
                .isEqualTo(ClientProjectCommand.State.RUNNING);
        assertThat(operations.find(tenant, "Owner", operation.id()).orElseThrow().state())
                .isEqualTo(ClientProjectOperation.State.APPLYING);
        assertThat(tasks.findForOwner(tenant, "Owner", dispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.DISPATCHED);
        assertThat(tasks.findForOwner(tenant, "Owner", fencedPending.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.PENDING);
        var recovered = ownership.takeOverForRecovery(tenant, "Owner", run.id(), 1,
                UUID.randomUUID(), Duration.ofSeconds(20))
                .orElseThrow();
        assertThat(recovered.epoch()).isEqualTo(2);
        assertThat(acknowledgements.current(device, run.id()).orElseThrow().phase())
                .isEqualTo(ClientProjectRunOwnership.Phase.RECOVERING);
        assertThat(acknowledgements.current(device, run.id()).orElseThrow().acknowledged()).isFalse();
        assertThat(acknowledgements.acknowledge(device, run.id(), 1)).isFalse();
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(),
                recovered.instanceId(), 2, ClientProjectRunOwnership.Phase.RECOVERING))).isFalse();
        assertThat(acknowledgements.acknowledge(device, run.id(), 2)).isTrue();
        assertThat(acknowledgements.acknowledge(device, run.id(), 2)).isTrue();
        assertThat(recoveryRepository.findAcknowledgedRecovering(recovered.instanceId(), 10))
                .anySatisfy(candidate -> assertThat(candidate.runId()).isEqualTo(run.id()));
        assertThat(transaction.<Boolean>execute(status -> reader.complete(device, fencedDispatched.id(), 2,
                ClientProjectTask.State.SUCCEEDED, "密文", ""))).isFalse();
        assertThat(transaction.<Boolean>execute(status -> acknowledgements.lockConfirmed(device, run.id(),
                recovered.instanceId(), 2, ClientProjectRunOwnership.Phase.RECOVERING))).isTrue();
        assertThat(audit.listByTenant(tenant, 100)).anyMatch(event -> !event.id().equals(auditId)
                && event.eventType().equals("CLIENT_PROJECT_RUN_RECOVERY")
                && event.principalId().equals("system:client-project-recovery")
                && event.resourceId().equals(run.id().toString())
                && event.message().contains("epoch=2") && event.message().contains("commands=1")
                && event.message().contains("operations=1") && event.message().contains("tasks=4"));
        assertThat(commands.find(tenant, "Owner", proposed.id()).orElseThrow().state())
                .isEqualTo(ClientProjectCommand.State.UNKNOWN);
        assertThat(operations.find(tenant, "Owner", operation.id()).orElseThrow().state())
                .isEqualTo(ClientProjectOperation.State.UNKNOWN);
        assertThat(tasks.findForOwner(tenant, "Owner", dispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.UNKNOWN);
        assertThat(tasks.findForOwner(tenant, "Owner", pending.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.CANCELLED);
        assertThat(tasks.findForOwner(tenant, "Owner", fencedPending.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.CANCELLED);
        assertThat(tasks.findForOwner(tenant, "Owner", fencedDispatched.id()).orElseThrow().state())
                .isEqualTo(ClientProjectTask.State.UNKNOWN);
    }

    /** 数据库时间、锁等待和代次竞争均在双方言的真实事务中验证。 */
    private void verifyRunOwnership(JdbcClient jdbc, TransactionTemplate transaction,
            ClientProjectBinding binding, UUID instance, ClientProjectRunOwnership first) throws Exception {
        var ownership = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction);
        assertThatThrownBy(() -> ownership.lockExecution(binding.tenantId(), "Owner",
                binding.runId(), instance, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(first.epoch()).isEqualTo(1);
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.insertInitial(binding, UUID.randomUUID(),
                Duration.ofSeconds(2)))).isEmpty();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.lockExecution(binding.tenantId(), "owner",
                binding.runId(), instance, 1))).isEmpty();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.lockExecution(UUID.randomUUID(), "Owner",
                binding.runId(), instance, 1))).isEmpty();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.lockExecution(binding.tenantId(), "Owner",
                binding.runId(), instance, 1))).isPresent();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.renew(binding.tenantId(),
                "Owner", binding.runId(), UUID.randomUUID(), 1, Duration.ofSeconds(6)))).isEmpty();
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.renew(binding.tenantId(),
                "Owner", binding.runId(), instance, 2, Duration.ofSeconds(6)))).isEmpty();
        var renewed = transaction.execute(status -> ownership.renew(binding.tenantId(), "Owner",
                binding.runId(), instance, 1, Duration.ofSeconds(6)).orElseThrow());
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.takeOverForRecovery(binding.tenantId(),
                "Owner", binding.runId(), 1, UUID.randomUUID(), Duration.ofSeconds(2)))).isEmpty();

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction.execute(status -> {
                assertThat(ownership.lockExecution(binding.tenantId(), "Owner", binding.runId(),
                        instance, 1)).isPresent();
                locked.countDown();
                try { if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("测试锁未释放"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return true;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var waiting = pool.submit(() -> transaction.execute(status -> ownership.lockExecution(
                        binding.tenantId(), "Owner", binding.runId(), instance, 1)));
                // 等锁跨越租期，不能把 PostgreSQL 事务开始时间当成当前数据库时间。
                Thread.sleep(6300);
                release.countDown();
                assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isEmpty();
            } finally { release.countDown(); }
        }

        // 同包注入失败屏障只用于验证事务回滚；公开构造器固定使用真实隔离与审计。
        var failingOwnership = new JdbcClientProjectRunOwnershipRepository(dataSource(transaction), transaction,
                (old, next) -> {
                    throw new IllegalStateException("合成隔离审计失败");
                });
        assertThatThrownBy(() -> failingOwnership.takeOverForRecovery(binding.tenantId(), "Owner", binding.runId(),
                1, UUID.randomUUID(), Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
        var rollbackInstance = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            assertThat(ownership.takeOverForRecovery(binding.tenantId(), "Owner", binding.runId(),
                    1, rollbackInstance, Duration.ofSeconds(5))).isPresent();
            status.setRollbackOnly();
        });
        var start = new CountDownLatch(1);
        UUID nextA = UUID.randomUUID(), nextB = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return transaction.execute(status ->
                    ownership.takeOverForRecovery(binding.tenantId(), "Owner", binding.runId(), 1,
                            nextA, Duration.ofSeconds(10))); });
            var b = pool.submit(() -> { start.await(); return transaction.execute(status ->
                    ownership.takeOverForRecovery(binding.tenantId(), "Owner", binding.runId(), 1,
                            nextB, Duration.ofSeconds(10))); });
            start.countDown();
            var results = List.of(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            var winner = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(winner.epoch()).isEqualTo(2);
            assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.lockRecovery(binding.tenantId(), "Owner",
                    binding.runId(), winner.instanceId(), 2))).isPresent();
            assertThat(transaction.<Optional<ClientProjectRunOwnership>>execute(status -> ownership.lockExecution(binding.tenantId(), "Owner",
                    binding.runId(), instance, 1))).isEmpty();
        }
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
