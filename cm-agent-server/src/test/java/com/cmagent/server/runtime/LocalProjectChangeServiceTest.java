package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.domain.WorkspaceContextResource;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.store.InMemoryLocalProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.KeyGenerator;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用自建临时根验证真实Windows副作用；内存合同替身不代表JDBC事务或跨实例并发验收。 */
@EnabledOnOs(OS.WINDOWS)
class LocalProjectChangeServiceTest {
    @TempDir Path temp;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectBinding binding;
    private PrincipalRef owner;
    private InMemoryLocalProjectRepository projects;
    private Store operations;
    private ModelCredentialCipher cipher;
    private AuditAppender audit;
    private MutableClock clock;
    private LocalProjectChangeService service;
    private Path state;

    @BeforeEach void setup() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-29T00:00:00Z"));
        owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试主体", Set.of("project:read", "project:write", "agent:run"));
        Path directory = Files.createDirectory(temp.resolve("project"));
        var tickets = new LocalProjectDirectoryTickets(clock, Duration.ofMinutes(2), 1);
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(owner, request, directory);
        root = tickets.consume(owner, request, ticket.id());
        binding = new LocalProjectBinding(owner.tenantId(), owner.principalId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), WorkspaceContextResource.digest(root.identity()),
                "test-node", 1, clock.instant());
        projects = new InMemoryLocalProjectRepository();
        projects.insert(new LocalProject(binding.projectId(), binding.tenantId(), binding.ownerId(), "自建项目", binding.nodeId(),
                binding.rootBindingId(), binding.rootIdentity(), LocalProject.State.ACTIVE, 1, clock.instant(), clock.instant()));
        operations = new Store();
        var generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        cipher = new ModelCredentialCipher(generator.generateKey());
        audit = mock(AuditAppender.class);
        state = temp.resolve("private-changes");
        service = makeService();
    }

    @AfterEach void close() throws Exception {
        if (service != null) service.close();
    }

    @Test void 提案只形成密文意图和受控差异且没有项目副作用() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("new.txt", "仅预览的合成正文")));

        try (var children = Files.list(root.directory())) {
            assertThat(children.toList()).isEmpty();
        }
        assertThat(proposal.state()).isEqualTo(LocalProjectOperation.State.PROPOSED);
        var operation = operation(proposal.operationId());
        assertThat(operation.encryptedPayload()).startsWith("v1:").doesNotContain("合成正文", "new.txt", root.directory().toString());
        assertThat(proposal.toString()).doesNotContain("合成正文", "new.txt");
        var preview = service.preview(owner, binding, root, proposal.operationId());
        assertThat(preview.changes()).singleElement().satisfies(change -> {
            assertThat(change.after()).isEqualTo("仅预览的合成正文");
            assertThat(change.path()).isEqualTo("new.txt");
        });
        assertThat(preview.toString()).doesNotContain("合成正文");
        for (Path journal : journals()) {
            assertThat(Files.readString(journal)).startsWith("v1:").doesNotContain("合成正文", "new.txt");
        }
        assertThat(mockingDetails(audit).getInvocations()).allSatisfy(invocation ->
                assertThat(Arrays.toString(invocation.getArguments())).doesNotContain("合成正文", "new.txt", root.directory().toString()));
    }

    @Test void 未批准或批准版本失配不能派发且来源主体精确匹配() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("approval.txt", "待批准")));
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 1)).isInstanceOf(IOException.class);
        prepared(proposal.operationId());
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 2)).isInstanceOf(IOException.class);
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.PREPARED);
        var other = new PrincipalRef(owner.tenantId(), "owner", "大小写不同主体", owner.permissions());
        assertThatThrownBy(() -> service.preview(other, binding, root, proposal.operationId())).isInstanceOf(SecurityException.class);
        var noWrite = new PrincipalRef(owner.tenantId(), owner.principalId(), "无写权限", Set.of("project:read", "agent:run"));
        assertThatThrownBy(() -> service.apply(noWrite, binding, root, proposal.operationId(), 3)).isInstanceOf(SecurityException.class);
        var anotherRun = new LocalProjectBinding(binding.tenantId(), binding.ownerId(), binding.projectId(), binding.agentId(),
                binding.conversationId(), UUID.randomUUID(), binding.rootBindingId(), binding.rootIdentity(), binding.nodeId(), 1, clock.instant());
        assertThatThrownBy(() -> service.apply(owner, anotherRun, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(Files.exists(root.directory().resolve("approval.txt"))).isFalse();
    }

    @Test void 六种文件目录动作执行后撤销必须经历全新批准周期() throws Exception {
        Files.writeString(root.directory().resolve("replace.txt"), "替换前");
        Files.writeString(root.directory().resolve("move.txt"), "移动原文");
        Files.writeString(root.directory().resolve("delete.txt"), "删除原文");
        Files.createDirectory(root.directory().resolve("old-empty"));
        var beforeSecurity = WindowsLocalProjectSecurity.capture(root.directory().resolve("delete.txt"));
        var beforeDirectorySecurity = WindowsLocalProjectSecurity.capture(root.directory().resolve("old-empty"));
        var proposal = service.propose(owner, binding, root, List.of(
                create("created.txt", "新增正文"),
                new LocalProjectChangeService.Change("REPLACE_FILE", "replace.txt", null, "替换后", sha("替换前")),
                new LocalProjectChangeService.Change("MOVE_FILE", "move.txt", "moved.txt", null, sha("移动原文")),
                new LocalProjectChangeService.Change("DELETE_FILE", "delete.txt", null, null, sha("删除原文")),
                new LocalProjectChangeService.Change("CREATE_DIRECTORY", "new-empty", null, null, null),
                new LocalProjectChangeService.Change("DELETE_EMPTY_DIRECTORY", "old-empty", null, null, null)));
        prepared(proposal.operationId());
        var applied = service.apply(owner, binding, root, proposal.operationId(), 3);
        assertThat(applied.state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThat(Files.readString(root.directory().resolve("replace.txt"))).isEqualTo("替换后");
        assertThat(Files.readString(root.directory().resolve("moved.txt"))).isEqualTo("移动原文");
        assertThat(Files.exists(root.directory().resolve("move.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("delete.txt"))).isFalse();
        assertThat(Files.isDirectory(root.directory().resolve("new-empty"))).isTrue();
        assertThat(Files.exists(root.directory().resolve("old-empty"))).isFalse();

        var undo = service.undoProposal(owner, binding, root, proposal.operationId());
        assertThat(undo.operationId()).isNotEqualTo(proposal.operationId());
        assertThat(undo.state()).isEqualTo(LocalProjectOperation.State.PROPOSED);
        assertThatThrownBy(() -> service.apply(owner, binding, root, undo.operationId(), undo.version())).isInstanceOf(IOException.class);
        assertThat(Files.readString(root.directory().resolve("replace.txt"))).isEqualTo("替换后");
        prepared(undo.operationId());
        assertThat(service.apply(owner, binding, root, undo.operationId(), 3).state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThat(Files.readString(root.directory().resolve("replace.txt"))).isEqualTo("替换前");
        assertThat(Files.readString(root.directory().resolve("move.txt"))).isEqualTo("移动原文");
        assertThat(Files.readString(root.directory().resolve("delete.txt"))).isEqualTo("删除原文");
        assertThat(WindowsLocalProjectSecurity.capture(root.directory().resolve("delete.txt")).fingerprint()).isEqualTo(beforeSecurity.fingerprint());
        assertThat(WindowsLocalProjectSecurity.capture(root.directory().resolve("old-empty")).fingerprint()).isEqualTo(beforeDirectorySecurity.fingerprint());
        assertThat(Files.exists(root.directory().resolve("created.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("moved.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("new-empty"))).isFalse();
    }

    @Test void 批准后原文变化在零写入证据下标记失败并返回安全冲突() throws Exception {
        Path file = Files.writeString(root.directory().resolve("conflict.txt"), "原文");
        var proposal = service.propose(owner, binding, root, List.of(
                new LocalProjectChangeService.Change("REPLACE_FILE", "conflict.txt", null, "批准正文", sha("原文"))));
        prepared(proposal.operationId());
        Files.writeString(file, "用户后续修改");
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOfSatisfying(LocalProjectChangeException.class,
                error -> assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.CONFLICT))
                .hasMessageNotContaining(file.toString()).hasMessageNotContaining("用户后续修改");
        assertThat(Files.readString(file)).isEqualTo("用户后续修改");
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(service.propose(owner, binding, root, List.of(create("fresh.txt", "新提案"))).state())
                .isEqualTo(LocalProjectOperation.State.PROPOSED);
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
        assertThat(Files.exists(root.directory().resolve("fresh.txt"))).isFalse();
    }

    @Test void 前一动作已写入而后一动作冲突仍保持未知并禁止重放() throws Exception {
        Path changed = Files.writeString(root.directory().resolve("later.txt"), "原文");
        var proposal = service.propose(owner, binding, root, List.of(create("first.txt", "已执行"),
                new LocalProjectChangeService.Change("REPLACE_FILE", "later.txt", null, "批准正文", sha("原文"))));
        prepared(proposal.operationId());
        Files.writeString(changed, "外部更新");
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3))
                .isInstanceOf(IOException.class).isNotInstanceOf(LocalProjectChangeException.class);
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(Files.readString(root.directory().resolve("first.txt"))).isEqualTo("已执行");
        assertThat(Files.readString(changed)).isEqualTo("外部更新");
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
    }

    @Test void 批准后原文件被移除时拒绝写入并标记冲突失败() throws Exception {
        Path file=Files.writeString(root.directory().resolve("removed.txt"),"原文");
        var proposal=service.propose(owner,binding,root,List.of(
                new LocalProjectChangeService.Change("REPLACE_FILE","removed.txt",null,"批准正文",sha("原文"))));
        prepared(proposal.operationId());
        Files.delete(file);
        assertThatThrownBy(()->service.apply(owner,binding,root,proposal.operationId(),3))
                .isInstanceOfSatisfying(LocalProjectChangeException.class,
                        error->assertThat(error.reason()).isEqualTo(LocalProjectChangeException.Reason.CONFLICT));
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test void 服务重建保留成功回执可撤销且不重复执行原操作() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("restart.txt", "重建后正文")));
        prepared(proposal.operationId());
        service.apply(owner, binding, root, proposal.operationId(), 3);
        service.close();
        service = makeService();
        assertThat(service.status(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        var undo = service.undoProposal(owner, binding, root, proposal.operationId());
        assertThat(undo.state()).isEqualTo(LocalProjectOperation.State.PROPOSED);
        assertThat(Files.readString(root.directory().resolve("restart.txt"))).isEqualTo("重建后正文");
    }

    @Test void 批准后同正文重建对象也不能借用旧批准覆盖() throws Exception {
        Path file = Files.writeString(root.directory().resolve("identity.txt"), "相同原文");
        String originalIdentity = WindowsLocalProjectSecurity.capture(file).identity();
        var proposal = service.propose(owner, binding, root, List.of(
                new LocalProjectChangeService.Change("REPLACE_FILE", "identity.txt", null, "拟替换正文", sha("相同原文"))));
        prepared(proposal.operationId());
        // 保留旧对象，避免文件系统立即复用同一对象编号导致测试偶然通过。
        Files.move(file, temp.resolve("original-identity.txt"));
        Files.writeString(file, "相同原文");
        assertThat(WindowsLocalProjectSecurity.capture(file).identity()).isNotEqualTo(originalIdentity);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("相同原文");
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
    }

    @Test void 成功后同正文重建对象拒绝生成删除式撤销() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("undo-identity.txt", "相同正文")));
        prepared(proposal.operationId());
        service.apply(owner, binding, root, proposal.operationId(), 3);
        Path file = root.directory().resolve("undo-identity.txt");
        String originalIdentity = WindowsLocalProjectSecurity.capture(file).identity();
        Files.move(file, temp.resolve("previous-success.txt"));
        Files.writeString(file, "相同正文");
        assertThat(WindowsLocalProjectSecurity.capture(file).identity()).isNotEqualTo(originalIdentity);
        int before = operations.values.size();
        assertThatThrownBy(() -> service.undoProposal(owner, binding, root, proposal.operationId())).isInstanceOf(IOException.class)
                .hasMessageContaining("对象或权限已变化");
        assertThat(operations.values).hasSize(before);
        assertThat(Files.readString(file)).isEqualTo("相同正文");
    }

    @Test void 批准后ACL变化拒绝写入且成功后ACL变化拒绝撤销() throws Exception {
        Path file = Files.writeString(root.directory().resolve("acl-before.txt"), "原始内容");
        var proposal = service.propose(owner, binding, root, List.of(
                new LocalProjectChangeService.Change("REPLACE_FILE", "acl-before.txt", null, "批准内容", sha("原始内容"))));
        prepared(proposal.operationId());
        changeAcl(file);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("原始内容");
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);

        var created = service.propose(owner, binding, root, List.of(create("acl-after.txt", "新增内容")));
        prepared(created.operationId());
        service.apply(owner, binding, root, created.operationId(), 3);
        Path after = root.directory().resolve("acl-after.txt");
        changeAcl(after);
        assertThatThrownBy(() -> service.undoProposal(owner, binding, root, created.operationId())).isInstanceOf(IOException.class)
                .hasMessageContaining("对象或权限已变化");
        assertThat(Files.readString(after)).isEqualTo("新增内容");
    }

    @Test void 混合未知状态在服务重建后仍不重放也不猜测收口() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("first.txt", "已执行"), create("second.txt", "未执行")));
        prepared(proposal.operationId());
        var running = operations.claimPrepared(owner.tenantId(), owner.principalId(), proposal.operationId(), 3, clock.instant()).orElseThrow();
        Files.writeString(root.directory().resolve("first.txt"), "已执行");
        operations.advance(owner.tenantId(), owner.principalId(), proposal.operationId(), running.version(),
                LocalProjectOperation.State.UNKNOWN, clock.instant()).orElseThrow();
        service.close();
        service = makeService();
        assertThat(service.status(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(Files.readString(root.directory().resolve("first.txt"))).isEqualTo("已执行");
        assertThat(Files.exists(root.directory().resolve("second.txt"))).isFalse();
    }

    @Test void 崩溃前取得派发权但没有写入时重建不执行且核对只收口失败() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("not-dispatched.txt", "不应自动写入")));
        prepared(proposal.operationId());
        operations.claimPrepared(owner.tenantId(), owner.principalId(), proposal.operationId(), 3, clock.instant()).orElseThrow();
        service.close();
        service = makeService();
        assertThat(service.status(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.RUNNING);
        assertThat(Files.exists(root.directory().resolve("not-dispatched.txt"))).isFalse();
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
        assertThat(Files.exists(root.directory().resolve("not-dispatched.txt"))).isFalse();
    }

    @Test void 到期清理擦除终态正文并取消已批准未派发意图() throws Exception {
        var cancelled = service.propose(owner, binding, root, List.of(create("expired.txt", "应擦除的合成内容")));
        operations.advance(owner.tenantId(), owner.principalId(), cancelled.operationId(), 1,
                LocalProjectOperation.State.CANCELLED, clock.instant()).orElseThrow();
        var waiting = service.propose(owner, binding, root, List.of(create("prepared.txt", "活动内容")));
        prepared(waiting.operationId());
        String originalPayload = operation(cancelled.operationId()).encryptedPayload();
        clock.advance(Duration.ofDays(7));
        assertThatThrownBy(() -> service.preview(owner, binding, root, cancelled.operationId())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.apply(owner, binding, root, waiting.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(service.cleanupExpired()).isEqualTo(2);
        var tombstone = operation(cancelled.operationId());
        assertThat(tombstone.state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        assertThat(tombstone.digest()).isEqualTo(cancelled.digest());
        assertThat(tombstone.encryptedPayload()).isNotEqualTo(originalPayload);
        assertThat(cipher.decrypt(tombstone.encryptedPayload())).contains("\"expired\":true").doesNotContain("合成内容");
        assertThat(service.status(owner, binding, root, cancelled.operationId()).count()).isZero();
        assertThat(operation(waiting.operationId()).state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        assertThat(cipher.decrypt(operation(waiting.operationId()).encryptedPayload())).contains("\"expired\":true");
        assertThat(journals()).isEmpty();
        assertThat(Files.exists(root.directory().resolve("expired.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("prepared.txt"))).isFalse();
    }

    @Test void 成功记录到期后不能撤销但不会删除已应用的文件() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("keep-success.txt", "成功文件")));
        prepared(proposal.operationId());
        service.apply(owner, binding, root, proposal.operationId(), 3);
        clock.advance(Duration.ofDays(7));
        assertThat(service.cleanupExpired()).isEqualTo(1);
        assertThat(journals()).isEmpty();
        assertThatThrownBy(() -> service.undoProposal(owner, binding, root, proposal.operationId())).isInstanceOf(IOException.class)
                .hasMessageContaining("恢复期限已到");
        assertThat(Files.readString(root.directory().resolve("keep-success.txt"))).isEqualTo("成功文件");
    }

    @Test void 写入已完成但结果持久化失败时重建只保留未知且不重放() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("result-failure.txt", "已经落盘")));
        prepared(proposal.operationId());
        operations.completionFailure = new org.springframework.dao.DataAccessResourceFailureException("合成存储不可用");
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(Files.readString(root.directory().resolve("result-failure.txt"))).isEqualTo("已经落盘");
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        service.close();
        service = makeService();
        assertThat(service.status(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(IOException.class);
        assertThat(Files.readString(root.directory().resolve("result-failure.txt"))).isEqualTo("已经落盘");
    }

    @Test void 撤销回执被破坏时拒绝猜测执行状态且不删除当前文件() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("receipt.txt", "回执保护文件")));
        prepared(proposal.operationId());
        service.apply(owner, binding, root, proposal.operationId(), 3);
        Path journal = journals().getFirst();
        String encrypted = Files.readString(journal);
        int position = encrypted.length() / 2;
        Files.writeString(journal, encrypted.substring(0, position) + (encrypted.charAt(position) == 'A' ? 'B' : 'A') + encrypted.substring(position + 1));
        assertThatThrownBy(() -> service.undoProposal(owner, binding, root, proposal.operationId())).isInstanceOf(IOException.class)
                .hasMessageContaining("回执不可核实");
        assertThat(Files.readString(root.directory().resolve("receipt.txt"))).isEqualTo("回执保护文件");
        assertThat(operations.values).hasSize(1);
    }

    @Test void 租户不符或项目归档后不能读取差异或继续写入() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("archive.txt", "归档后不写入")));
        prepared(proposal.operationId());
        var otherTenant = new PrincipalRef(UUID.randomUUID(), owner.principalId(), "其他租户", owner.permissions());
        assertThatThrownBy(() -> service.preview(otherTenant, binding, root, proposal.operationId())).isInstanceOf(SecurityException.class);
        assertThat(projects.archive(binding.tenantId(), binding.ownerId(), binding.projectId(), 1, clock.instant())).isTrue();
        assertThatThrownBy(() -> service.preview(owner, binding, root, proposal.operationId())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3)).isInstanceOf(SecurityException.class);
        assertThat(Files.exists(root.directory().resolve("archive.txt"))).isFalse();
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.PREPARED);
    }

    @Test void 恢复区达到容量上限时拒绝新增意图且不触碰项目() throws Exception {
        service.propose(owner, binding, root, List.of(create("budget-first.txt", "首个意图")));
        Path directory = state.resolve(binding.tenantId().toString()).resolve(binding.projectId().toString());
        // 仅在自建恢复区建立稀疏占位，验证密文磁盘预算；不分配或操作任何真实项目文件。
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("budget.reserve"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(100L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        int before = operations.values.size();
        assertThatThrownBy(() -> service.propose(owner, binding, root, List.of(create("budget-second.txt", "超额意图"))))
                .isInstanceOf(IOException.class).hasMessageContaining("容量不足");
        assertThat(operations.values).hasSize(before);
        assertThat(Files.exists(root.directory().resolve("budget-second.txt"))).isFalse();
    }

    @Test void 六动作均保持批准前状态时核对仅收口失败而不执行() throws Exception {
        var proposal = service.propose(owner, binding, root, sixChanges());
        prepared(proposal.operationId());
        operations.claimPrepared(owner.tenantId(), owner.principalId(), proposal.operationId(), 3, clock.instant()).orElseThrow();
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.FAILED);
        assertThat(Files.readString(root.directory().resolve("replace.txt"))).isEqualTo("替换前");
        assertThat(Files.readString(root.directory().resolve("move.txt"))).isEqualTo("移动原文");
        assertThat(Files.readString(root.directory().resolve("delete.txt"))).isEqualTo("删除原文");
        assertThat(Files.isDirectory(root.directory().resolve("old-empty"))).isTrue();
        assertThat(Files.exists(root.directory().resolve("created.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("new-empty"))).isFalse();
    }

    @Test void 六动作已执行但结果提交失败时核对成功保留原回执以支持条件撤销() throws Exception {
        var proposal = service.propose(owner, binding, root, sixChanges());
        prepared(proposal.operationId());
        operations.completionFailure = new org.springframework.dao.DataAccessResourceFailureException("合成结果提交失败");
        assertThatThrownBy(() -> service.apply(owner, binding, root, proposal.operationId(), 3))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        service.close();
        service = makeService();
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        var undo = service.undoProposal(owner, binding, root, proposal.operationId());
        prepared(undo.operationId());
        assertThat(service.apply(owner, binding, root, undo.operationId(), 3).state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThat(Files.readString(root.directory().resolve("replace.txt"))).isEqualTo("替换前");
        assertThat(Files.readString(root.directory().resolve("move.txt"))).isEqualTo("移动原文");
        assertThat(Files.readString(root.directory().resolve("delete.txt"))).isEqualTo("删除原文");
        assertThat(Files.isDirectory(root.directory().resolve("old-empty"))).isTrue();
        assertThat(Files.exists(root.directory().resolve("created.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("moved.txt"))).isFalse();
        assertThat(Files.exists(root.directory().resolve("new-empty"))).isFalse();
    }

    @Test void 新建文件或目录缺写后回执时同目标状态也保持未知() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("unreceipted.txt", "目标正文"),
                new LocalProjectChangeService.Change("CREATE_DIRECTORY", "unreceipted-directory", null, null, null)));
        prepared(proposal.operationId());
        var running = operations.claimPrepared(owner.tenantId(), owner.principalId(), proposal.operationId(), 3, clock.instant()).orElseThrow();
        Files.writeString(root.directory().resolve("unreceipted.txt"), "目标正文");
        Files.createDirectory(root.directory().resolve("unreceipted-directory"));
        operations.advance(owner.tenantId(), owner.principalId(), proposal.operationId(), running.version(),
                LocalProjectOperation.State.UNKNOWN, clock.instant()).orElseThrow();
        assertThat(service.reconcile(owner, binding, root, proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThatThrownBy(() -> service.undoProposal(owner, binding, root, proposal.operationId())).isInstanceOf(IOException.class);
        assertThat(Files.readString(root.directory().resolve("unreceipted.txt"))).isEqualTo("目标正文");
        assertThat(Files.isDirectory(root.directory().resolve("unreceipted-directory"))).isTrue();
    }

    @Test void 三类到期未派发意图均先取消擦除而执行中意图保留核对() throws Exception {
        var draft = service.propose(owner, binding, root, List.of(create("draft.txt", "草稿")));
        var waiting = service.propose(owner, binding, root, List.of(create("waiting.txt", "待批准")));
        operations.advance(owner.tenantId(), owner.principalId(), waiting.operationId(), 1, LocalProjectOperation.State.WAITING_APPROVAL, clock.instant()).orElseThrow();
        var prepared = service.propose(owner, binding, root, List.of(create("prepared-expire.txt", "已批准")));
        prepared(prepared.operationId());
        var running = service.propose(owner, binding, root, List.of(create("running-expire.txt", "执行中")));
        prepared(running.operationId());
        operations.claimPrepared(owner.tenantId(), owner.principalId(), running.operationId(), 3, clock.instant()).orElseThrow();
        String runningPayload = operation(running.operationId()).encryptedPayload();
        clock.advance(Duration.ofDays(7));
        assertThat(service.cleanupExpired()).isEqualTo(3);
        for (UUID id : List.of(draft.operationId(), waiting.operationId(), prepared.operationId())) {
            assertThat(operation(id).state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
            assertThat(cipher.decrypt(operation(id).encryptedPayload())).contains("\"expired\":true");
        }
        assertThat(operation(running.operationId()).state()).isEqualTo(LocalProjectOperation.State.RUNNING);
        assertThat(operation(running.operationId()).encryptedPayload()).isEqualTo(runningPayload);
        assertThat(journals()).hasSize(1);
    }

    @Test void 到期取消与派发竞争时原版本CAS不能取消已取得派发权的运行() throws Exception {
        var proposal = service.propose(owner, binding, root, List.of(create("expiry-race.txt", "不会被GC执行")));
        prepared(proposal.operationId());
        String original = operation(proposal.operationId()).encryptedPayload();
        operations.claimBeforeExpiry = proposal.operationId();
        clock.advance(Duration.ofDays(7));
        assertThat(service.cleanupExpired()).isZero();
        assertThat(operation(proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.RUNNING);
        assertThat(operation(proposal.operationId()).encryptedPayload()).isEqualTo(original);
        assertThat(journals()).hasSize(1);
        assertThat(Files.exists(root.directory().resolve("expiry-race.txt"))).isFalse();
    }

    @Test void 首批百条未到期记录不会使后续到期记录永久无法清理() throws Exception {
        for (int i = 0; i < 102; i++) service.propose(owner, binding, root, List.of(create("gc-" + i + ".txt", "合成意图")));
        Path directory = state.resolve(binding.tenantId().toString()).resolve(binding.projectId().toString());
        List<Path> order = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) order.add(path);
        }
        UUID expiredId = UUID.fromString(order.getLast().getFileName().toString().replace(".gcm", ""));
        // 测试仓储合成后续六天创建的记录，只有目录枚举末项保持旧创建时间，以固定扫描饥饿回归场景。
        for (var entry : new ArrayList<>(operations.values.entrySet())) {
            var value = entry.getValue();
            if (value.id().equals(expiredId)) continue;
            Instant newer = value.createdAt().plus(Duration.ofDays(6));
            operations.values.put(value.id(), new LocalProjectOperation(value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                    value.agentId(), value.conversationId(), value.runId(), value.kind(), value.digest(), value.encryptedPayload(),
                    value.state(), value.version(), newer, newer));
        }
        clock.advance(Duration.ofDays(7));
        assertThat(service.cleanupExpired()).isZero();
        int cleaned = 0;
        for (int round = 0; round < 4; round++) cleaned += service.cleanupExpired();
        assertThat(cleaned).isEqualTo(1);
        assertThat(operation(expiredId).state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        assertThat(journals()).hasSize(101);
        assertThat(operations.values.values().stream().filter(value -> value.state() == LocalProjectOperation.State.PROPOSED).count()).isEqualTo(101);
    }

    private List<LocalProjectChangeService.Change> sixChanges() throws IOException {
        Files.writeString(root.directory().resolve("replace.txt"), "替换前");
        Files.writeString(root.directory().resolve("move.txt"), "移动原文");
        Files.writeString(root.directory().resolve("delete.txt"), "删除原文");
        Files.createDirectory(root.directory().resolve("old-empty"));
        return List.of(create("created.txt", "新增正文"),
                new LocalProjectChangeService.Change("REPLACE_FILE", "replace.txt", null, "替换后", sha("替换前")),
                new LocalProjectChangeService.Change("MOVE_FILE", "move.txt", "moved.txt", null, sha("移动原文")),
                new LocalProjectChangeService.Change("DELETE_FILE", "delete.txt", null, null, sha("删除原文")),
                new LocalProjectChangeService.Change("CREATE_DIRECTORY", "new-empty", null, null, null),
                new LocalProjectChangeService.Change("DELETE_EMPTY_DIRECTORY", "old-empty", null, null, null));
    }

    private static void changeAcl(Path file) throws IOException {
        var before = WindowsLocalProjectSecurity.capture(file);
        var view = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        var entries = new ArrayList<>(view.getAcl());
        // 在保留已有完整许可的前提下新增合成显式ACE，仅改变权限版本，不锁死测试目录。
        entries.add(0, java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(view.getOwner()).setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build());
        view.setAcl(entries);
        assertThat(WindowsLocalProjectSecurity.capture(file).fingerprint()).isNotEqualTo(before.fingerprint());
    }

    private LocalProjectChangeService makeService() throws IOException {
        // 此替身仅提供TransactionTemplate生命周期，不实现回滚；真实JDBC原子claim另由仓储集成测试验收。
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> new SimpleTransactionStatus());
        return new LocalProjectChangeService(projects, operations, cipher, new ObjectMapper().findAndRegisterModules(),
                audit, new TransactionTemplate(manager), clock, binding.nodeId(), state);
    }

    private LocalProjectOperation operation(UUID id) {
        return operations.operation(owner.tenantId(), owner.principalId(), id).orElseThrow();
    }

    private void prepared(UUID id) {
        operations.advance(owner.tenantId(), owner.principalId(), id, 1, LocalProjectOperation.State.WAITING_APPROVAL, clock.instant()).orElseThrow();
        operations.advance(owner.tenantId(), owner.principalId(), id, 2, LocalProjectOperation.State.PREPARED, clock.instant()).orElseThrow();
    }

    private List<Path> journals() throws IOException {
        try (var paths = Files.walk(state)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".gcm")).toList();
        }
    }

    private static LocalProjectChangeService.Change create(String path, String content) {
        return new LocalProjectChangeService.Change("CREATE_FILE", path, null, content, null);
    }

    private static String sha(String content) { return WorkspaceContextResource.digest(content); }

    /** 内存替身以同一监视器模拟条件派发合同，不能替代数据库项目行锁验证。 */
    private static final class Store implements LocalProjectOperationRepository {
        /** 只保留本测试生成的合成意图。 */
        private final Map<UUID, LocalProjectOperation> values = new HashMap<>();
        /** 单次模拟执行完成后的仓储故障，观察文件系统与数据库无法原子提交的窗口。 */
        private RuntimeException completionFailure;
        /** 单次模拟GC读取PREPARED后、取消CAS前另一派发者先取得运行权。 */
        private UUID claimBeforeExpiry;

        @Override public synchronized boolean insertOperation(LocalProjectOperation value) {
            return values.putIfAbsent(value.id(), value) == null;
        }
        @Override public synchronized Optional<LocalProjectOperation> operation(UUID tenant, String owner, UUID id) {
            return Optional.ofNullable(values.get(id)).filter(value -> value.tenantId().equals(tenant) && value.ownerId().equals(owner));
        }
        @Override public synchronized Optional<LocalProjectOperation> advance(UUID tenant, String owner, UUID id, long version,
                LocalProjectOperation.State next, Instant now) {
            var old = operation(tenant, owner, id).orElse(null);
            if (old == null || old.version() != version) return Optional.empty();
            if (next == LocalProjectOperation.State.CANCELLED && id.equals(claimBeforeExpiry)
                    && old.state() == LocalProjectOperation.State.PREPARED) {
                claimBeforeExpiry = null;
                values.put(id, old.advance(LocalProjectOperation.State.RUNNING, now));
                return Optional.empty();
            }
            if (next == LocalProjectOperation.State.SUCCEEDED && completionFailure != null) {
                var failure = completionFailure;
                completionFailure = null;
                throw failure;
            }
            var updated = old.advance(next, now);
            values.put(id, updated);
            return Optional.of(updated);
        }
        @Override public synchronized boolean hasUncertainOperation(UUID tenant, String owner, UUID project, UUID except) {
            return values.values().stream().anyMatch(value -> value.tenantId().equals(tenant) && value.ownerId().equals(owner)
                    && value.projectId().equals(project) && !value.id().equals(except)
                    && (value.state() == LocalProjectOperation.State.RUNNING || value.state() == LocalProjectOperation.State.UNKNOWN));
        }
        @Override public synchronized Optional<LocalProjectOperation> claimPrepared(UUID tenant, String owner, UUID id, long version, Instant now) {
            var old = operation(tenant, owner, id).orElse(null);
            if (old == null || old.state() != LocalProjectOperation.State.PREPARED
                    || hasUncertainOperation(tenant, owner, old.projectId(), id)) return Optional.empty();
            return advance(tenant, owner, id, version, LocalProjectOperation.State.RUNNING, now);
        }
        @Override public synchronized boolean erasePayload(UUID tenant, String owner, UUID id, long version, String replacement, Instant now) {
            var old = operation(tenant, owner, id).orElse(null);
            if (old == null || old.version() != version || old.createdAt().plus(Duration.ofDays(7)).isAfter(now)
                    || !Set.of(LocalProjectOperation.State.SUCCEEDED, LocalProjectOperation.State.FAILED,
                    LocalProjectOperation.State.CANCELLED, LocalProjectOperation.State.UNKNOWN).contains(old.state())) return false;
            values.put(id, new LocalProjectOperation(old.id(), old.tenantId(), old.ownerId(), old.projectId(), old.agentId(),
                    old.conversationId(), old.runId(), old.kind(), old.digest(), replacement, old.state(), old.version() + 1, old.createdAt(), now));
            return true;
        }
    }

    /** 可控服务时钟只改变测试时间，不等待真实七天。 */
    private static final class MutableClock extends Clock {
        /** 本测试实例持有的当前时间。 */
        private Instant current;
        MutableClock(Instant now) { current = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
        void advance(Duration duration) { current = current.plus(duration); }
    }
}
