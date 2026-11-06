package com.cmagent.persistence;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.domain.LocalProjectRoot;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.repository.LocalProjectOperationRepository.CommandRetentionCursor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.assertj.core.api.Assertions.*;

/**
 * T10 的项目级恢复及分页回归：只在 ssh rocky 的指定 Maven21 容器运行。
 * 每个场景新建合成租户及项目，数据库生命周期由 Testcontainers 持有；不执行任何本机文件或命令副作用。
 */
@Testcontainers
class JdbcLocalProjectOperationRecoveryTest {
    /** 本类独占的 PostgreSQL16 合成库，不连接现有业务库。 */
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 本类独占的 MySQL8.4 合成库，用于复验精确 owner 及行锁语义。 */
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test void PostgreSQL文件与命令跨Run只能一次抢占且未知状态阻断() throws Exception {
        verifySnapshotConflict(new Fixture(postgres));
        verifyClaims(new Fixture(postgres));
    }
    @Test void MySQL文件与命令跨Run只能一次抢占且未知状态阻断() throws Exception {
        verifySnapshotConflict(new Fixture(mysql));
        verifyClaims(new Fixture(mysql));
    }
    @Test void PostgreSQL精确归属与归档拒绝派发() { verifyOwnership(new Fixture(postgres)); }
    @Test void MySQL精确归属与归档拒绝派发() { verifyOwnership(new Fixture(mysql)); }
    @Test void PostgreSQL过期擦除只清密文并保留摘要及未知状态() { verifyErasure(new Fixture(postgres)); }
    @Test void MySQL过期擦除只清密文并保留摘要及未知状态() { verifyErasure(new Fixture(mysql)); }
    @Test void PostgreSQL归档项目历史按复合游标分页并在截断前过滤篡改() { verifyConversations(new Fixture(postgres)); }
    @Test void MySQL归档项目历史按复合游标分页并在截断前过滤篡改() { verifyConversations(new Fixture(mysql)); }
    @Test void PostgreSQL来源头降级仍永久保护直接与派生会话运行() { verifySourceGuard(new Fixture(postgres)); }
    @Test void MySQL来源头降级仍永久保护直接与派生会话运行() { verifySourceGuard(new Fixture(mysql)); }
    @Test void PostgreSQL固定Run操作分页归属过滤稳定排序与未知收口回滚() { verifyOperationList(new Fixture(postgres)); }
    @Test void MySQL固定Run操作分页归属过滤稳定排序与未知收口回滚() { verifyOperationList(new Fixture(mysql)); }
    @Test void PostgreSQL命令保留期发现限定节点精确归属且复合游标遍历所有状态() { verifyRetentionCandidates(postgres); }
    @Test void MySQL命令保留期发现限定节点精确归属且复合游标遍历所有状态() { verifyRetentionCandidates(mysql); }
    @Test void PostgreSQL冷启动仅发现当前节点活动运行并以完整CAS收口() { verifyColdStart(postgres); }
    @Test void MySQL冷启动仅发现当前节点活动运行并以完整CAS收口() { verifyColdStart(mysql); }
    @Test void PostgreSQL归档活动见证不因来源降级丢失且CAS审计可回滚() { verifyArchive(postgres); }
    @Test void MySQL归档活动见证不因来源降级丢失且CAS审计可回滚() { verifyArchive(mysql); }

    private static void verifyArchive(JdbcDatabaseContainer<?> database) {
        var f = new Fixture(database); var operation = f.proposed(LocalProjectOperation.Kind.COMMAND, f.now);
        assertThat(f.bindings.hasActiveRuns(f.tenant, "Owner", f.project.id())).isTrue();
        assertThat(f.bindings.hasActiveRuns(f.tenant, "owner", f.project.id())).isFalse();
        assertThat(f.bindings.hasActiveRuns(UUID.randomUUID(), "Owner", f.project.id())).isFalse();
        assertThat(f.bindings.hasActiveRuns(f.tenant, "Owner", UUID.randomUUID())).isFalse();
        // 已知绑定的活动事实优先于损坏的协议/kind/当前Run主体，不能借降级把在途任务藏起来。
        for (String state : List.of("RUNNING", "WAITING_APPROVAL", "CANCELLING")) {
            f.jdbc.sql("UPDATE runs SET status=:state,workspace_protocol=0,run_kind='TEST',principal_id='owner' WHERE tenant_id=:tenant AND id=:id")
                    .param("state", state).param("tenant", f.tenant.toString()).param("id", operation.runId().toString()).update();
            assertThat(f.bindings.hasActiveRuns(f.tenant, "Owner", f.project.id())).isTrue();
        }
        f.jdbc.sql("UPDATE runs SET status='FAILED',finished_at=:now WHERE tenant_id=:tenant AND id=:id")
                .param("now", Timestamp.from(f.now.plusSeconds(1))).param("tenant", f.tenant.toString()).param("id", operation.runId().toString()).update();
        assertThat(f.bindings.hasActiveRuns(f.tenant, "Owner", f.project.id())).isFalse();
        assertThat(f.projects.archive(f.tenant, "owner", f.project.id(), 1, f.now.plusSeconds(1))).isFalse();
        assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 2, f.now.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> {
            assertThat(f.projects.lock(f.tenant, "Owner", f.project.id())).isPresent();
            assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 1, f.now.plusSeconds(1))).isTrue();
            throw new IllegalStateException("合成归档审计失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(f.projects.find(f.tenant, "Owner", f.project.id()).orElseThrow().state()).isEqualTo(LocalProject.State.ACTIVE);
        f.tx.executeWithoutResult(status -> {
            assertThat(f.projects.lock(f.tenant, "Owner", f.project.id())).isPresent();
            assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 1, f.now.plusSeconds(1))).isTrue();
        });
        assertThat(f.projects.find(f.tenant, "Owner", f.project.id()).orElseThrow().state()).isEqualTo(LocalProject.State.ARCHIVED);
        assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 1, f.now.plusSeconds(2))).isFalse();
        assertThat(f.bindings.conversation(f.tenant, "Owner", f.initial.id())).isPresent();
        assertThat(f.projects.root(f.tenant, "Owner", f.root.id())).isPresent();
        assertThat(f.operations.operation(f.tenant, "Owner", operation.id())).isPresent();
    }

    private static void verifyColdStart(JdbcDatabaseContainer<?> database) {
        String node = "cold-" + UUID.randomUUID();
        var f = new Fixture(database, node); var otherTenant = new Fixture(database, node); var foreign = new Fixture(database, node + "-foreign");
        var filePrepared = f.prepared(LocalProjectOperation.Kind.FILE_CHANGE, f.now);
        var running = f.operations.advance(f.tenant, "Owner", filePrepared.id(), filePrepared.version(), LocalProjectOperation.State.RUNNING, f.now).orElseThrow();
        var cancelling = f.inState(LocalProjectOperation.State.RUNNING, f.now);
        var unknown = f.inState(LocalProjectOperation.State.UNKNOWN, f.now);
        var waiting = f.prepared(LocalProjectOperation.Kind.FILE_CHANGE, f.now);
        var runs = new JdbcRunRepository(f.jdbc);
        var cancelSnapshot = runs.findByTenantAndAgentAndId(f.tenant, f.agent, cancelling.runId()).orElseThrow();
        assertThat(runs.requestCancellation(cancelSnapshot)).isTrue();
        runs.waitForApproval(f.tenant, waiting.runId());
        var other = otherTenant.inState(LocalProjectOperation.State.RUNNING, otherTenant.now);
        var foreignOperation = foreign.inState(LocalProjectOperation.State.RUNNING, foreign.now);
        var corrupt = f.inState(LocalProjectOperation.State.RUNNING, f.now);
        f.jdbc.sql("UPDATE runs SET principal_id='owner' WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", f.tenant.toString()).param("id", corrupt.runId().toString()).update();
        var downgraded = f.inState(LocalProjectOperation.State.RUNNING, f.now);
        f.jdbc.sql("UPDATE runs SET workspace_protocol=0 WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", f.tenant.toString()).param("id", downgraded.runId().toString()).update();
        var testKind = f.inState(LocalProjectOperation.State.RUNNING, f.now);
        f.jdbc.sql("UPDATE runs SET run_kind='TEST' WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", f.tenant.toString()).param("id", testKind.runId().toString()).update();
        var discovered = f.bindings.findInterruptedRuns(node, 100);
        assertThat(discovered).extracting(LocalProjectBinding::runId).containsExactlyInAnyOrder(running.runId(), cancelling.runId(), unknown.runId(), other.runId());
        assertThat(f.bindings.findInterruptedRuns(node.toUpperCase(java.util.Locale.ROOT), 100)).isEmpty();
        assertThatThrownBy(() -> f.bindings.findInterruptedRuns(node, 101)).isInstanceOf(IllegalArgumentException.class);
        var binding = f.bindings.run(f.tenant, "Owner", running.runId()).orElseThrow();
        assertThat(f.operations.findRunningForRun(binding, 100)).containsExactly(running);
        assertThat(f.operations.findRunningForRun(f.bindings.run(f.tenant, "Owner", unknown.runId()).orElseThrow(), 100)).isEmpty();
        var snapshot = runs.findByTenantAndAgentAndId(f.tenant, f.agent, running.runId()).orElseThrow();
        var wrongOwner = new RunRecord(snapshot.id(), snapshot.tenantId(), snapshot.agentId(), "owner", snapshot.kind(), snapshot.status(), snapshot.input(), snapshot.output(), snapshot.errorMessage(), snapshot.startedAt(), snapshot.finishedAt());
        assertThat(runs.failInterrupted(wrongOwner, f.now.plusSeconds(1))).isFalse();
        // 模拟最末严格审计失败；UNKNOWN和FAILED必须同事务回滚，不能留下部分冷启动结果。
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> {
            assertThat(f.operations.advance(f.tenant, "Owner", running.id(), running.version(), LocalProjectOperation.State.UNKNOWN, f.now.plusSeconds(1))).isPresent();
            assertThat(runs.failInterrupted(snapshot, f.now.plusSeconds(1))).isTrue();
            throw new IllegalStateException("合成严格审计拒绝");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(f.operations.operation(f.tenant, "Owner", running.id()).orElseThrow()).isEqualTo(running);
        assertThat(runs.findByTenantAndAgentAndId(f.tenant, f.agent, running.runId()).orElseThrow().status()).isEqualTo(com.cmagent.core.domain.RunStatus.RUNNING);
        var cancellingCurrent = runs.findByTenantAndAgentAndId(f.tenant, f.agent, cancelling.runId()).orElseThrow();
        assertThat(runs.failInterrupted(cancelSnapshot, f.now.plusSeconds(1))).isFalse();
        assertThat(runs.failInterrupted(cancellingCurrent, f.now.plusSeconds(1))).isTrue();
        var failed = runs.findByTenantAndAgentAndId(f.tenant, f.agent, cancelling.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(com.cmagent.core.domain.RunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo(com.cmagent.core.repository.RunRepository.INTERRUPTED_FAILURE_MESSAGE);
        assertThat(runs.failInterrupted(cancellingCurrent, f.now.plusSeconds(2))).isFalse();
        assertThat(runs.failInterrupted(runs.findByTenantAndAgentAndId(f.tenant, f.agent, waiting.runId()).orElseThrow(), f.now.plusSeconds(1))).isFalse();
        assertThat(f.operations.operation(f.tenant, "Owner", unknown.id()).orElseThrow()).isEqualTo(unknown);
        assertThat(foreign.bindings.findInterruptedRuns(node, 100)).extracting(LocalProjectBinding::runId).doesNotContain(foreignOperation.runId());
    }

    private static void verifyRetentionCandidates(JdbcDatabaseContainer<?> database){
        String node="retention-"+UUID.randomUUID();
        var f=new Fixture(database,node);
        var otherTenant=new Fixture(database,node);
        Instant old=f.now.minus(Duration.ofDays(8));
        Instant cutoff=f.now.minus(Duration.ofDays(7));
        var expected=new ArrayList<LocalProjectOperation>();
        for(var state:LocalProjectOperation.State.values()){
            var value=f.inState(state,old);
            if(state!=LocalProjectOperation.State.RUNNING)expected.add(value);
        }
        f.proposed(LocalProjectOperation.Kind.COMMAND,cutoff.plusNanos(1000));
        f.proposed(LocalProjectOperation.Kind.FILE_CHANGE,old.minusSeconds(3));
        var wrongOwner=f.proposed(LocalProjectOperation.Kind.COMMAND,old.minusSeconds(2));
        f.jdbc.sql("UPDATE local_project_operations SET owner_id='owner' WHERE tenant_id=:tenant AND id=:id")
                .param("tenant",f.tenant.toString()).param("id",wrongOwner.id().toString()).update();
        var foreignRoot=new LocalProjectRoot(UUID.randomUUID(),f.tenant,"Owner",node+"-foreign",encrypted("合成其他节点根"),"c".repeat(64),f.now);
        assertThat(f.projects.insertRoot(foreignRoot)).isTrue();
        var foreignProject=new LocalProject(UUID.randomUUID(),f.tenant,"Owner","合成其他节点项目",foreignRoot.nodeId(),foreignRoot.id(),foreignRoot.identity(),LocalProject.State.ACTIVE,1,f.now,f.now);
        assertThat(f.projects.insert(foreignProject)).isTrue();
        var foreignNode=f.proposed(LocalProjectOperation.Kind.COMMAND,old.minusSeconds(1));
        f.jdbc.sql("UPDATE local_project_operations SET project_id=:project WHERE tenant_id=:tenant AND id=:id")
                .param("project",foreignProject.id().toString()).param("tenant",f.tenant.toString()).param("id",foreignNode.id().toString()).update();
        var exact=f.proposed(LocalProjectOperation.Kind.COMMAND,cutoff);expected.add(exact);
        // 相同创建时间和UUID允许分属不同租户；若cursor漏tenant，页边界会跳过其中一个。
        var same=otherTenant.proposed(LocalProjectOperation.Kind.COMMAND,old);
        var source=expected.getFirst();
        otherTenant.jdbc.sql("UPDATE local_project_operations SET id=:next WHERE tenant_id=:tenant AND id=:id")
                .param("next",source.id().toString()).param("tenant",otherTenant.tenant.toString()).param("id",same.id().toString()).update();
        expected.add(new LocalProjectOperation(source.id(),same.tenantId(),same.ownerId(),same.projectId(),same.agentId(),same.conversationId(),same.runId(),
                same.kind(),same.digest(),same.encryptedPayload(),same.state(),same.version(),same.createdAt(),same.updatedAt()));
        expected.sort(Comparator.comparing(LocalProjectOperation::createdAt).thenComparing(value->value.id().toString()).thenComparing(value->value.tenantId().toString()));
        assertThat(f.projects.archive(f.tenant,"Owner",f.project.id(),1,f.now)).isTrue();
        var found=new ArrayList<LocalProjectOperation>();
        CommandRetentionCursor cursor=null;
        for(int page=0;page<20;page++){
            var values=f.operations.findCommandRetentionCandidates(node,cutoff,cursor,1);
            if(values.isEmpty())break;
            found.addAll(values);var last=values.getLast();cursor=new CommandRetentionCursor(last.createdAt(),last.id(),last.tenantId());
        }
        assertThat(found).containsExactlyElementsOf(expected);
        assertThat(f.operations.findCommandRetentionCandidates(node.toUpperCase(java.util.Locale.ROOT),cutoff,null,100)).isEmpty();
        assertThat(f.operations.findCommandRetentionCandidates(node+"-other",cutoff,null,100)).isEmpty();
        assertThatThrownBy(()->f.operations.findCommandRetentionCandidates(node,cutoff,null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.operations.findCommandRetentionCandidates(node,cutoff,null,101)).isInstanceOf(IllegalArgumentException.class);
        // 擦除会保留墓碑，稳定游标仍能走过它读到后续条目；归档不能让旧密文永远失去清理入口。
        var terminal=expected.stream().filter(value->value.state()==LocalProjectOperation.State.SUCCEEDED).findFirst().orElseThrow();
        assertThat(f.operations.erasePayload(terminal.tenantId(),terminal.ownerId(),terminal.id(),terminal.version(),encrypted("合成到期墓碑"),f.now)).isTrue();
        var afterTerminal=new CommandRetentionCursor(terminal.createdAt(),terminal.id(),terminal.tenantId());
        assertThat(f.operations.findCommandRetentionCandidates(node,cutoff,afterTerminal,100))
                .containsExactlyElementsOf(expected.subList(expected.indexOf(terminal)+1,expected.size()));
    }

    private static void verifyOperationList(Fixture f) {
        var source = f.proposed(LocalProjectOperation.Kind.COMMAND, f.now.minusSeconds(100));
        var newest = anotherOperation(f, source, UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2"), f.now.plusSeconds(10));
        var tied = anotherOperation(f, source, UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1"), newest.createdAt());
        var older = anotherOperation(f, source, UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0"), f.now.plusSeconds(9));
        // 更晚的污染行必须在 LIMIT 前过滤；这里直接改列只模拟损坏存储，正式写入仍由仓储核验绑定。
        var wrongOwner = anotherOperation(f, source, UUID.randomUUID(), f.now.plusSeconds(30));
        f.jdbc.sql("UPDATE local_project_operations SET owner_id='owner' WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", f.tenant.toString()).param("id", wrongOwner.id().toString()).update();
        var otherProject = new LocalProject(UUID.randomUUID(), f.tenant, "Owner", "合成另一项目", f.root.nodeId(), f.root.id(),
                f.root.identity(), LocalProject.State.ACTIVE, 1, f.now, f.now);
        assertThat(f.projects.insert(otherProject)).isTrue();
        var wrongProject = anotherOperation(f, source, UUID.randomUUID(), f.now.plusSeconds(31));
        f.jdbc.sql("UPDATE local_project_operations SET project_id=:project WHERE tenant_id=:tenant AND id=:id")
                .param("project", otherProject.id().toString()).param("tenant", f.tenant.toString()).param("id", wrongProject.id().toString()).update();
        f.proposed(LocalProjectOperation.Kind.FILE_CHANGE, f.now.plusSeconds(32));
        assertThat(f.operations.listRun(f.tenant, "Owner", f.project.id(), source.runId(), 2)).containsExactly(newest, tied);
        assertThat(f.operations.listRun(f.tenant, "Owner", f.project.id(), source.runId(), 100)).containsExactly(newest, tied, older, source);
        for (String principal : List.of("Owner ", "another"))
            assertThat(f.operations.listRun(f.tenant, principal, f.project.id(), source.runId(), 1)).isEmpty();
        assertThat(f.operations.listRun(f.tenant, "owner", f.project.id(), source.runId(), 1)).hasSize(1)
                .first().satisfies(value -> assertThat(value.id()).isEqualTo(wrongOwner.id()));
        assertThat(f.operations.listRun(UUID.randomUUID(), "Owner", f.project.id(), source.runId(), 1)).isEmpty();
        assertThat(f.operations.listRun(f.tenant, "Owner", UUID.randomUUID(), source.runId(), 1)).isEmpty();
        assertThat(f.operations.listRun(f.tenant, "Owner", f.project.id(), UUID.randomUUID(), 1)).isEmpty();
        assertThatThrownBy(() -> f.operations.listRun(f.tenant, "Owner", f.project.id(), source.runId(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.operations.listRun(f.tenant, "Owner", f.project.id(), source.runId(), 101)).isInstanceOf(IllegalArgumentException.class);

        var waiting = f.operations.advance(f.tenant, "Owner", source.id(), 1, LocalProjectOperation.State.WAITING_APPROVAL, f.now).orElseThrow();
        var prepared = f.operations.advance(f.tenant, "Owner", source.id(), waiting.version(), LocalProjectOperation.State.PREPARED, f.now).orElseThrow();
        var unknown = f.operations.advance(f.tenant, "Owner", source.id(), prepared.version(), LocalProjectOperation.State.UNKNOWN, f.now).orElseThrow();
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> {
            f.operations.advance(f.tenant, "Owner", source.id(), unknown.version(), LocalProjectOperation.State.CANCELLED, f.now).orElseThrow();
            throw new IllegalStateException("合成最终审计失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("合成最终审计失败");
        assertThat(f.operations.operation(f.tenant, "Owner", source.id())).contains(unknown);
        assertThat(f.operations.hasUncertainOperation(f.tenant, "Owner", f.project.id(), null)).isTrue();
        var cancelled = f.operations.advance(f.tenant, "Owner", source.id(), unknown.version(), LocalProjectOperation.State.CANCELLED, f.now).orElseThrow();
        assertThat(cancelled.digest()).isEqualTo(source.digest());
        assertThat(f.operations.hasUncertainOperation(f.tenant, "Owner", f.project.id(), null)).isFalse();
        assertThat(f.operations.advance(f.tenant, "Owner", source.id(), unknown.version(), LocalProjectOperation.State.CANCELLED, f.now)).isEmpty();
        assertThatThrownBy(() -> f.operations.advance(f.tenant, "Owner", source.id(), cancelled.version(), LocalProjectOperation.State.RUNNING, f.now))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LocalProjectOperation anotherOperation(Fixture f, LocalProjectOperation source, UUID id, Instant created) {
        var value = new LocalProjectOperation(id, f.tenant, "Owner", f.project.id(), f.agent, source.conversationId(), source.runId(),
                LocalProjectOperation.Kind.FILE_CHANGE, source.digest(), encrypted("合成同Run清单"), LocalProjectOperation.State.PROPOSED, 1, created, created);
        assertThat(f.operations.insertOperation(value)).isTrue();
        return value;
    }

    private static void verifySourceGuard(Fixture f) {
        var runs = new JdbcRunRepository(f.jdbc);
        var source = f.proposed(LocalProjectOperation.Kind.COMMAND, f.now);
        var sourceRun = runs.findByTenantAndAgentAndId(f.tenant, f.agent, source.runId()).orElseThrow();
        var derived = new Conversation(UUID.randomUUID(), f.tenant, f.agent, "合成派生私有会话", "Owner", f.now, f.now);
        f.conversations.save(f.tenant, derived);
        var derivedRun = RunRecord.create(UUID.randomUUID(), f.tenant, f.agent, "Owner", "合成派生私有输入", f.now);
        runs.save(f.tenant, derivedRun);
        var inProjectConversation = RunRecord.create(UUID.randomUUID(), f.tenant, f.agent, "Owner", "合成项目会话派生输入", f.now);
        runs.save(f.tenant, inProjectConversation);
        var messages = new JdbcConversationMessageRepository(f.jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), f.tx);
        for (var pair : List.of(new UUID[]{derived.id(), sourceRun.id()}, new UUID[]{derived.id(), derivedRun.id()},
                new UUID[]{f.initial.id(), inProjectConversation.id()})) {
            messages.append(f.tenant, new com.cmagent.core.domain.ConversationMessageDraft(UUID.randomUUID(), f.tenant, pair[0],
                    com.cmagent.core.domain.MessageRole.USER, "合成关联", List.of(com.cmagent.core.domain.MessageContentBlock.text("合成关联")), pair[1], f.now));
        }
        var ordinary = new Conversation(UUID.randomUUID(), f.tenant, f.agent, "普通旧来源会话", "Owner", f.now.minusSeconds(1), f.now.minusSeconds(1));
        f.conversations.save(f.tenant, ordinary);
        var ordinaryRun = RunRecord.create(UUID.randomUUID(), f.tenant, f.agent, "Owner", "普通旧来源输入", f.now.minusSeconds(1));
        runs.save(f.tenant, ordinaryRun);
        for (int protocol : new int[]{0, 3}) {
            f.changeConversation(f.initial.id(), "workspace_protocol", protocol);
            f.jdbc.sql("UPDATE runs SET workspace_protocol=:protocol WHERE tenant_id=:tenant AND id=:id")
                    .param("protocol", protocol).param("tenant", f.tenant.toString()).param("id", sourceRun.id().toString()).update();
            for (var conversation : List.of(f.initial, derived)) {
                assertThat(f.conversations.hasLocalProjectSource(f.tenant, f.agent, conversation.id())).isTrue();
                assertThat(f.conversations.hasLocalProjectSource(UUID.randomUUID(), f.agent, conversation.id())).isFalse();
                assertThat(f.conversations.hasLocalProjectSource(f.tenant, UUID.randomUUID(), conversation.id())).isFalse();
                assertThat(runs.visibleConversationFromWorkspace(conversation, "Owner")).isFalse();
            }
            for (var run : List.of(sourceRun, derivedRun, inProjectConversation)) {
                assertThat(runs.hasLocalProjectSource(f.tenant, f.agent, run.id())).isTrue();
                assertThat(runs.hasLocalProjectSource(UUID.randomUUID(), f.agent, run.id())).isFalse();
                assertThat(runs.hasLocalProjectSource(f.tenant, UUID.randomUUID(), run.id())).isFalse();
            }
            // 分页只含普通资源，不能先查出降级的私有行再由页面隐藏；旧三态仍保持原可见合同。
            for (String viewer : List.of("Owner", "owner", "another")) {
                assertThat(f.conversations.listVisible(f.tenant, f.agent, viewer, new ConversationPageRequest(1, null, null)))
                        .containsExactly(ordinary);
                assertThat(runs.listVisible(f.tenant, f.agent, viewer, new com.cmagent.core.domain.RunPageRequest(1, null, null)))
                        .containsExactly(ordinaryRun);
            }
        }
        assertThat(f.conversations.hasLocalProjectSource(f.tenant, f.agent, ordinary.id())).isFalse();
        assertThat(runs.hasLocalProjectSource(f.tenant, f.agent, ordinaryRun.id())).isFalse();
    }

    /** 固定先建立普通读快照再提交竞争者，确定性覆盖 MySQL RR，避免只靠线程调度偶然暴露旧快照。 */
    private static void verifySnapshotConflict(Fixture f) throws Exception {
        var file = f.prepared(LocalProjectOperation.Kind.FILE_CHANGE, f.now);
        var command = f.prepared(LocalProjectOperation.Kind.COMMAND, f.now);
        var snapshotReady = new CountDownLatch(1);
        var competitorCommitted = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var staleTransaction = pool.submit(() -> f.tx.execute(status -> {
                assertThat(f.operations.operation(f.tenant, "Owner", file.id())).contains(file);
                snapshotReady.countDown();
                try {
                    if (!competitorCommitted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("合成竞争者未及时提交");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("合成快照测试已中断");
                }
                return f.operations.claimPrepared(f.tenant, "Owner", file.id(), file.version(), f.now);
            }));
            try {
                assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(f.operations.claimPrepared(f.tenant, "Owner", command.id(), command.version(), f.now)).isPresent();
            } finally {
                competitorCommitted.countDown();
            }
            assertThat(staleTransaction.get(20, TimeUnit.SECONDS)).isEmpty();
            assertThat(f.operations.operation(f.tenant, "Owner", file.id())).contains(file);
        } finally {
            competitorCommitted.countDown();
        }
    }

    private static void verifyClaims(Fixture f) throws Exception {
        var file = f.prepared(LocalProjectOperation.Kind.FILE_CHANGE, f.now);
        var command = f.prepared(LocalProjectOperation.Kind.COMMAND, f.now);
        assertThat(file.runId()).isNotEqualTo(command.runId());
        // 两线程先同时就绪再放行；每次调用使用独立连接，不能用同一外层事务假装跨调用竞争。
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("合成竞争未及时放行");
                return f.operations.claimPrepared(f.tenant, "Owner", file.id(), file.version(), f.now);
            });
            var second = pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("合成竞争未及时放行");
                return f.operations.claimPrepared(f.tenant, "Owner", command.id(), command.version(), f.now);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var left = first.get(20, TimeUnit.SECONDS);
            var right = second.get(20, TimeUnit.SECONDS);
            assertThat((left.isPresent() ? 1 : 0) + (right.isPresent() ? 1 : 0)).isEqualTo(1);
            var winner = left.orElseGet(right::orElseThrow);
            var loser = winner.id().equals(file.id()) ? command : file;
            assertThat(f.operations.hasUncertainOperation(f.tenant, "Owner", f.project.id(), null)).isTrue();
            assertThat(f.operations.hasUncertainOperation(f.tenant, "Owner", f.project.id(), winner.id())).isFalse();
            for (String other : List.of("owner", "Owner ", "another"))
                assertThat(f.operations.hasUncertainOperation(f.tenant, other, f.project.id(), null)).isFalse();
            assertThat(f.operations.hasUncertainOperation(UUID.randomUUID(), "Owner", f.project.id(), null)).isFalse();
            var unknown = f.operations.advance(f.tenant, "Owner", winner.id(), winner.version(),
                    LocalProjectOperation.State.UNKNOWN, f.now).orElseThrow();
            assertThat(f.operations.claimPrepared(f.tenant, "Owner", loser.id(), loser.version(), f.now)).isEmpty();
            assertThat(f.operations.claimPrepared(f.tenant, "Owner", unknown.id(), unknown.version(), f.now)).isEmpty();
            assertThat(f.operations.operation(f.tenant, "Owner", loser.id()).orElseThrow().state())
                    .isEqualTo(LocalProjectOperation.State.PREPARED);
            // 仅受控核对把 UNKNOWN 收口后才允许另一个提案；不能通过重复 claim 自动重放原操作。
            f.operations.advance(f.tenant, "Owner", unknown.id(), unknown.version(), LocalProjectOperation.State.FAILED, f.now).orElseThrow();
            assertThat(f.operations.claimPrepared(f.tenant, "Owner", loser.id(), loser.version(), f.now)).isPresent();
            assertThat(f.operations.claimPrepared(f.tenant, "Owner", winner.id(), unknown.version() + 1, f.now)).isEmpty();
        } finally {
            start.countDown();
        }
    }

    private static void verifyOwnership(Fixture f) {
        var prepared = f.prepared(LocalProjectOperation.Kind.COMMAND, f.now);
        for (String other : List.of("owner", "Owner ", "another")) {
            assertThat(f.operations.operation(f.tenant, other, prepared.id())).isEmpty();
            assertThat(f.operations.claimPrepared(f.tenant, other, prepared.id(), prepared.version(), f.now)).isEmpty();
            assertThat(f.operations.erasePayload(f.tenant, other, prepared.id(), prepared.version(), encrypted("合成擦除"), f.now.plus(Duration.ofDays(8)))).isFalse();
            assertThat(f.bindings.listConversations(f.tenant, other, f.project.id(), new ConversationPageRequest(1, null, null))).isEmpty();
            var locked = f.tx.execute(status -> f.projects.lock(f.tenant, other, f.project.id()));
            assertThat(locked).isEmpty();
        }
        UUID anotherTenant = UUID.randomUUID();
        assertThat(f.operations.operation(anotherTenant, "Owner", prepared.id())).isEmpty();
        assertThat(f.operations.claimPrepared(anotherTenant, "Owner", prepared.id(), prepared.version(), f.now)).isEmpty();
        assertThat(f.operations.erasePayload(anotherTenant, "Owner", prepared.id(), prepared.version(), encrypted("合成擦除"), f.now.plus(Duration.ofDays(8)))).isFalse();
        assertThat(f.bindings.listConversations(anotherTenant, "Owner", f.project.id(), new ConversationPageRequest(1, null, null))).isEmpty();
        var otherTenantLock = f.tx.execute(status -> f.projects.lock(anotherTenant, "Owner", f.project.id()));
        assertThat(otherTenantLock).isEmpty();
        var ownerLock = f.tx.execute(status -> f.projects.lock(f.tenant, "Owner", f.project.id()));
        assertThat(ownerLock).contains(f.project);
        assertThat(f.operations.claimPrepared(f.tenant, "Owner", prepared.id(), prepared.version() - 1, f.now)).isEmpty();
        assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 1, f.now)).isTrue();
        assertThat(f.operations.claimPrepared(f.tenant, "Owner", prepared.id(), prepared.version(), f.now)).isEmpty();
        assertThat(f.operations.operation(f.tenant, "Owner", prepared.id())).contains(prepared);
    }

    private static void verifyErasure(Fixture f) {
        Instant old = f.now.minus(Duration.ofDays(8));
        for (var state : LocalProjectOperation.State.values()) {
            var operation = f.inState(state, old);
            String replacement = encrypted("合成过期载荷墓碑");
            boolean terminalOrUnknown = List.of(LocalProjectOperation.State.SUCCEEDED, LocalProjectOperation.State.FAILED,
                    LocalProjectOperation.State.CANCELLED, LocalProjectOperation.State.UNKNOWN).contains(state);
            assertThat(f.operations.erasePayload(f.tenant, "Owner", operation.id(), operation.version() - 1, replacement, f.now)).isFalse();
            assertThat(f.operations.erasePayload(f.tenant, "Owner", operation.id(), operation.version(), replacement,
                    old.plus(Duration.ofDays(7)).minusSeconds(1))).isFalse();
            assertThat(f.operations.erasePayload(f.tenant, "Owner", operation.id(), operation.version(), replacement, f.now))
                    .as("状态 %s 的清理资格", state).isEqualTo(terminalOrUnknown);
            var saved = f.operations.operation(f.tenant, "Owner", operation.id()).orElseThrow();
            assertThat(saved.digest()).isEqualTo(operation.digest());
            assertThat(saved.state()).isEqualTo(operation.state());
            assertThat(saved.createdAt()).isEqualTo(operation.createdAt());
            assertThat(saved.projectId()).isEqualTo(operation.projectId());
            assertThat(saved.runId()).isEqualTo(operation.runId());
            assertThat(saved.version()).isEqualTo(operation.version() + (terminalOrUnknown ? 1 : 0));
            assertThat(saved.encryptedPayload()).isEqualTo(terminalOrUnknown ? replacement : operation.encryptedPayload());
            if (state == LocalProjectOperation.State.UNKNOWN) {
                assertThat(f.operations.hasUncertainOperation(f.tenant, "Owner", f.project.id(), null)).isTrue();
                assertThat(f.operations.claimPrepared(f.tenant, "Owner", saved.id(), saved.version(), f.now)).isEmpty();
            }
        }
        // 七日边界本身可清理；错误归属不得因为已经终结而获得密文删除权限。
        var boundary = f.inState(LocalProjectOperation.State.CANCELLED, old);
        assertThat(f.operations.erasePayload(f.tenant, "owner", boundary.id(), boundary.version(), encrypted("拒绝"), f.now)).isFalse();
        assertThat(f.operations.erasePayload(UUID.randomUUID(), "Owner", boundary.id(), boundary.version(), encrypted("拒绝"), f.now)).isFalse();
        assertThat(f.operations.erasePayload(f.tenant, "Owner", boundary.id(), boundary.version(), encrypted("边界"), old.plus(Duration.ofDays(7)))).isTrue();
    }

    private static void verifyConversations(Fixture f) {
        var newest = f.conversation(UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2"), f.now.plusSeconds(10));
        var tied = f.conversation(UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1"), newest.updatedAt());
        var older = f.conversation(UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0"), f.now.plusSeconds(9));
        // 这些污染行比正常数据更新；若先 LIMIT 后过滤，第一页将不完整或出现错误来源。
        var wrongHeader = f.conversation(UUID.randomUUID(), f.now.plusSeconds(30));
        var importedHeader = f.conversation(UUID.randomUUID(), f.now.plusSeconds(31));
        var wrongCreator = f.conversation(UUID.randomUUID(), f.now.plusSeconds(32));
        var wrongBindingOwner = f.conversation(UUID.randomUUID(), f.now.plusSeconds(33));
        var wrongAgent = f.conversation(UUID.randomUUID(), f.now.plusSeconds(34));
        f.changeConversation(wrongHeader.id(), "workspace_protocol", 0);
        f.changeConversation(importedHeader.id(), "workspace_protocol", 3);
        f.changeConversation(wrongCreator.id(), "created_by", "owner");
        f.jdbc.sql("UPDATE local_project_conversations SET owner_id='owner' WHERE tenant_id=:tenant AND conversation_id=:id")
                .param("tenant", f.tenant.toString()).param("id", wrongBindingOwner.id().toString()).update();
        UUID anotherAgent = UUID.randomUUID();
        f.seedAgent(anotherAgent);
        f.changeConversation(wrongAgent.id(), "agent_id", anotherAgent.toString());
        var first = f.bindings.listConversations(f.tenant, "Owner", f.project.id(), new ConversationPageRequest(2, null, null));
        assertThat(first).containsExactly(newest, tied);
        var next = new ConversationPageRequest(2, newest.updatedAt(), newest.id());
        assertThat(f.bindings.listConversations(f.tenant, "Owner", f.project.id(), next)).containsExactly(tied, older);
        var afterTied = new ConversationPageRequest(2, tied.updatedAt(), tied.id());
        assertThat(f.bindings.listConversations(f.tenant, "Owner", f.project.id(), afterTied)).containsExactly(older, f.initial);
        assertThat(f.projects.archive(f.tenant, "Owner", f.project.id(), 1, f.now.plusSeconds(40))).isTrue();
        assertThat(f.bindings.listConversations(f.tenant, "Owner", f.project.id(), new ConversationPageRequest(2, null, null)))
                .containsExactly(newest, tied);
        assertThat(f.conversations.listVisible(f.tenant, f.agent, "Owner", new ConversationPageRequest(100, null, null)))
                .doesNotContain(newest, tied, older, f.initial);
        var postArchive = new Conversation(UUID.randomUUID(), f.tenant, f.agent, "归档后不新增绑定", "Owner", f.now, f.now);
        f.conversations.save(f.tenant, postArchive);
        assertThat(f.bindings.insertConversation(f.binding(postArchive, null))).isFalse();
    }

    /** 合成租户通过真实仓储建立来源；只有 tamper 场景直接修改列，模拟损坏存储而非产品授权入口。 */
    private static final class Fixture {
        /** 场景独立租户，避免多个测试共享行锁或副作用状态。 */
        private final UUID tenant = UUID.randomUUID();
        /** 场景唯一模型，不连接外部模型。 */
        private final UUID model = UUID.randomUUID();
        /** 项目会话固定的 Agent。 */
        private final UUID agent = UUID.randomUUID();
        /** 数据库微秒精度的合成时钟，确保双库时间游标一致。 */
        // 旧MySQL会话表沿用TIMESTAMP秒精度；本夹具同时构造会话和新项目记录，使用两库共同精度。
        // 来源/排序测试仍显式构造同时间UUID游标；操作擦除的微秒边界由独立偏移断言覆盖，不更改生产时间映射。
        private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        /** Testcontainers 数据源的具名参数入口。 */
        private final JdbcClient jdbc;
        /** 和各仓储共享数据源的短事务模板。 */
        private final TransactionTemplate tx;
        /** 项目行锁与归档入口。 */
        private final JdbcLocalProjectRepository projects;
        /** 固定来源及项目专页入口。 */
        private final JdbcLocalProjectBindingRepository bindings;
        /** 持久副作用意图入口，不执行操作本身。 */
        private final JdbcLocalProjectOperationRepository operations;
        /** 旧会话来源的兼容读取入口。 */
        private final JdbcConversationRepository conversations;
        /** 合成根，只存测试密文及身份摘要。 */
        private final LocalProjectRoot root;
        /** 场景唯一项目，owner 严格为 Owner。 */
        private final LocalProject project;
        /** 排序最早的合法来源会话。 */
        private final Conversation initial;

        private Fixture(JdbcDatabaseContainer<?> database) {
            this(database,"local-node");
        }

        private Fixture(JdbcDatabaseContainer<?> database,String nodeId) {
            var data = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            CmAgentFlyway.configure(data).load().migrate();
            jdbc = JdbcClient.create(data);
            tx = new TransactionTemplate(new DataSourceTransactionManager(data));
            projects = new JdbcLocalProjectRepository(jdbc, tx);
            bindings = new JdbcLocalProjectBindingRepository(jdbc, tx);
            operations = new JdbcLocalProjectOperationRepository(jdbc, tx);
            conversations = new JdbcConversationRepository(jdbc);
            jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成恢复租户',true,:now)")
                    .param("id", tenant.toString()).param("now", Timestamp.from(now)).update();
            jdbc.sql("""
                    INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at)
                    VALUES(:id,:tenant,'OPENAI_COMPATIBLE','合成模型','https://example.invalid','fixture','not-configured',true,:now)
                    """).param("id", model.toString()).param("tenant", tenant.toString()).param("now", Timestamp.from(now)).update();
            seedAgent(agent);
            root = new LocalProjectRoot(UUID.randomUUID(), tenant, "Owner", nodeId, encrypted("合成根"), "a".repeat(64), now);
            project = new LocalProject(UUID.randomUUID(), tenant, "Owner", "合成恢复项目", root.nodeId(), root.id(), root.identity(),
                    LocalProject.State.ACTIVE, 1, now, now);
            assertThat(projects.insertRoot(root)).isTrue();
            assertThat(projects.insert(project)).isTrue();
            initial = conversation(UUID.randomUUID(), now);
        }

        private void seedAgent(UUID id) {
            jdbc.sql("""
                    INSERT INTO agent_definitions(id,tenant_id,name,description,system_prompt,model_provider_id,model_name,temperature,
                      max_iterations,enabled,tool_ids_json,created_by,updated_by,created_at,updated_at)
                    VALUES(:id,:tenant,'合成Agent','','合成提示',:model,'fixture',0,4,true,'[]','Owner','Owner',:now,:now)
                    """).param("id", id.toString()).param("tenant", tenant.toString()).param("model", model.toString())
                    .param("now", Timestamp.from(now)).update();
        }

        private Conversation conversation(UUID id, Instant updated) {
            var value = new Conversation(id, tenant, agent, "合成固定项目会话", "Owner", now, updated);
            conversations.save(tenant, value);
            assertThat(bindings.insertConversation(binding(value, null))).isTrue();
            return value;
        }

        private LocalProjectBinding binding(Conversation value, UUID run) {
            return new LocalProjectBinding(tenant, "Owner", project.id(), agent, value.id(), run,
                    root.id(), root.identity(), root.nodeId(), project.version(), now);
        }

        private LocalProjectOperation proposed(LocalProjectOperation.Kind kind, Instant created) {
            var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "合成恢复运行", now);
            new JdbcRunRepository(jdbc).save(tenant, run);
            assertThat(bindings.insertRun(binding(initial, run.id()))).isTrue();
            var value = new LocalProjectOperation(UUID.randomUUID(), tenant, "Owner", project.id(), agent, initial.id(), run.id(),
                    kind, "b".repeat(64), encrypted("合成精确清单"), LocalProjectOperation.State.PROPOSED, 1, created, created);
            assertThat(operations.insertOperation(value)).isTrue();
            return value;
        }

        private LocalProjectOperation prepared(LocalProjectOperation.Kind kind, Instant created) {
            var value = proposed(kind, created);
            value = operations.advance(tenant, "Owner", value.id(), value.version(), LocalProjectOperation.State.WAITING_APPROVAL, created).orElseThrow();
            return operations.advance(tenant, "Owner", value.id(), value.version(), LocalProjectOperation.State.PREPARED, created).orElseThrow();
        }

        private LocalProjectOperation inState(LocalProjectOperation.State state, Instant created) {
            var value = proposed(LocalProjectOperation.Kind.COMMAND, created);
            if (state == LocalProjectOperation.State.PROPOSED) return value;
            value = operations.advance(tenant, "Owner", value.id(), value.version(), LocalProjectOperation.State.WAITING_APPROVAL, created).orElseThrow();
            if (state == LocalProjectOperation.State.WAITING_APPROVAL) return value;
            if (state == LocalProjectOperation.State.CANCELLED)
                return operations.advance(tenant, "Owner", value.id(), value.version(), state, created).orElseThrow();
            value = operations.advance(tenant, "Owner", value.id(), value.version(), LocalProjectOperation.State.PREPARED, created).orElseThrow();
            if (state == LocalProjectOperation.State.PREPARED) return value;
            // 这里仅为擦除资格建立持久状态；有意不用 claim，以免前一条 UNKNOWN 阻断测试布置。
            value = operations.advance(tenant, "Owner", value.id(), value.version(), LocalProjectOperation.State.RUNNING, created).orElseThrow();
            if (state == LocalProjectOperation.State.RUNNING) return value;
            return operations.advance(tenant, "Owner", value.id(), value.version(), state, created).orElseThrow();
        }

        private void changeConversation(UUID conversation, String fixedColumn, Object value) {
            if (!List.of("workspace_protocol", "created_by", "agent_id").contains(fixedColumn))
                throw new IllegalArgumentException("合成篡改只允许固定列");
            jdbc.sql("UPDATE conversations SET " + fixedColumn + "=:value WHERE tenant_id=:tenant AND id=:id")
                    .param("value", value).param("tenant", tenant.toString()).param("id", conversation.toString()).update();
        }
    }

    /** 随机内存密钥只生成合法测试信封；不把固定可用密钥、真实路径或凭据落入测试数据。 */
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
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("合成加密不可用");
        }
    }
}
