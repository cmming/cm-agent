package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.store.InMemoryRuntimeCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 临时目录上的真实读取与加密检查点回归，不调用模型、JDBC或真实项目，不把正文写到普通日志。 */
class LocalProjectReadSnapshotsTest {
    @TempDir Path temp;
    private PrincipalRef owner;
    private LocalProjectBinding binding;
    private LocalProjectAccessService.Scope scope;
    private LocalProjectFiles files;
    private ModelCredentialCipher cipher;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final InMemoryRuntimeCheckpointRepository store = new InMemoryRuntimeCheckpointRepository();
    private final LocalProjectAccessService access = mock(LocalProjectAccessService.class);
    private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-29T00:00:00Z"));
    private Clock clock;
    private LocalProjectReadSnapshots snapshots;

    @BeforeEach void setup() throws Exception {
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试主体", Set.of("project:read"));
        var directory = Files.createDirectory(temp.resolve("project"));
        var tickets = new LocalProjectDirectoryTickets(clock, Duration.ofMinutes(2), 1);
        UUID request = UUID.randomUUID(); var ticket = tickets.issue(owner, request, directory);
        var root = tickets.consume(owner, request, ticket.id());
        binding = new LocalProjectBinding(owner.tenantId(), owner.principalId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), WorkspaceContextResource.digest(root.identity()), "test-node", 1, clock.instant());
        var project = new LocalProject(binding.projectId(), binding.tenantId(), binding.ownerId(), "快照测试", binding.nodeId(),
                binding.rootBindingId(), binding.rootIdentity(), LocalProject.State.ACTIVE, 1, clock.instant(), clock.instant());
        scope = new LocalProjectAccessService.Scope(owner, project, root, binding);
        files = spy(new LocalProjectFiles(List.of())); cipher = randomCipher();
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new LocalProjectAccessException(call.getArgument(2), "snapshot-test-error", call.getArgument(3), call.getArgument(4)));
        when(access.infrastructure(any(), any(), anyString(), any())).thenReturn(new LocalProjectAccessException("PROJECT_UNAVAILABLE", "snapshot-test-error", 503, "读取快照不可用"));
        when(access.fileFailure(any(), any(), any())).thenReturn(new LocalProjectAccessException("PROJECT_FILE_UNAVAILABLE", "snapshot-test-error", 422, "文件不可用"));
        snapshots = make(cipher); snapshots.initialize(owner, binding);
    }

    @Test void 首次全文固定后外部修改不影响后续分页且正文仅入密文槽() throws Exception {
        String original = "原版本测试正文\n".repeat(10_000); write("source.txt", original);
        var first = read("source.txt", 0, 65536, null); write("source.txt", "外部已修改版本");
        var second = read("source.txt", first.nextOffset(), 65536, first.sha256());
        assertThat(first.sha256()).isEqualTo(sha(original)); assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(first.content() + second.content()).isEqualTo(original.substring(0, (first.content() + second.content()).length()));
        verify(files, times(1)).mutationSnapshot(scope.root(), "source.txt");
        assertThat(row().encryptedPayload()).startsWith("v1:").doesNotContain("原版本测试正文", "source.txt", temp.toString());
        assertThat(row().expiresAt()).isEqualTo(time.get().plus(Duration.ofMinutes(2)));
    }

    @Test void 同密钥跨实例恢复不重新读取已删磁盘文件() throws Exception {
        write("source.txt", "固定正文"); var first = read("source.txt", 0, 1024, null);
        Files.delete(scope.root().directory().resolve("source.txt"));
        var restored = make(cipher); restored.verify(owner, binding);
        var next = restored.read(owner, scope, "source.txt", 0, 1024, first.sha256());
        assertThat(next.content()).isEqualTo("固定正文"); verify(files, times(1)).mutationSnapshot(scope.root(), "source.txt");
    }

    @Test void 错误密钥缺失和到期均拒绝且不读取最新磁盘() throws Exception {
        write("source.txt", "最初版本"); read("source.txt", 0, 1024, null); write("source.txt", "不能回退的外部版本");
        var wrong = make(randomCipher());
        assertThatThrownBy(() -> wrong.verify(owner, binding)).isInstanceOf(LocalProjectAccessException.class);
        assertThatThrownBy(() -> wrong.read(owner, scope, "source.txt", 0, 1024, null)).isInstanceOf(LocalProjectAccessException.class);
        time.set(row().expiresAt());
        assertThatThrownBy(() -> snapshots.read(owner, scope, "source.txt", 0, 1024, null)).isInstanceOf(LocalProjectAccessException.class);
        snapshots.cleanup(binding);
        assertThatThrownBy(() -> make(cipher).read(owner, scope, "source.txt", 0, 1024, null)).isInstanceOf(LocalProjectAccessException.class);
        verify(files, times(1)).mutationSnapshot(scope.root(), "source.txt");
    }

    @Test void 用户引用摘要不符必须明确冲突且不能覆盖已冻结版本() throws Exception {
        write("source.txt", "原正文"); var first = read("source.txt", 0, 1024, null);
        assertThatThrownBy(() -> read("source.txt", 0, 1024, "f".repeat(64))).isInstanceOfSatisfying(LocalProjectAccessException.class,
                error -> { assertThat(error.status()).isEqualTo(409); assertThat(error.errorCode()).isEqualTo("PROJECT_FILE_FILE_CHANGED"); });
        assertThat(read("source.txt", 0, 1024, first.sha256()).content()).isEqualTo("原正文");
        verify(files, times(1)).mutationSnapshot(scope.root(), "source.txt");
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void Windows大小写别名沿用同一首次版本() throws Exception {
        write("ReadMe.txt", "同一版本"); var first = read("ReadMe.txt", 0, 1024, null); write("ReadMe.txt", "外部不同版本");
        var alias = read("README.TXT", 0, 1024, first.sha256());
        assertThat(alias.content()).isEqualTo("同一版本"); verify(files, times(1)).mutationSnapshot(any(), anyString());
    }

    @Test void 批准计划更新已读和未读文件且不采用外部磁盘新内容() throws Exception {
        write("old.txt", "已读旧内容"); read("old.txt", 0, 1024, null);
        var intent = preview(new LocalProjectChangeService.Diff("REPLACE_FILE", "old.txt", null, sha("已读旧内容"), "已读旧内容", "精确批准新内容"),
                new LocalProjectChangeService.Diff("CREATE_FILE", "new.txt", null, "", "", "精确批准新文件"));
        snapshots.validateApply(owner, binding, intent);
        write("old.txt", "外部抢改内容"); write("new.txt", "未读文件的外部替换内容");
        snapshots.afterApply(owner, binding, intent);
        assertThat(read("old.txt", 0, 1024, sha("精确批准新内容")).content()).isEqualTo("精确批准新内容");
        assertThat(read("new.txt", 0, 1024, sha("精确批准新文件")).content()).isEqualTo("精确批准新文件");
        verify(files, times(1)).mutationSnapshot(scope.root(), "old.txt"); verify(files, never()).mutationSnapshot(scope.root(), "new.txt");
    }

    @Test void 未读移动和删除也形成墓碑不允许重读后来同名文件() throws Exception {
        var intent = preview(new LocalProjectChangeService.Diff("MOVE_FILE", "from.txt", "to.txt", sha("批准内容"), "批准内容", "批准内容"),
                new LocalProjectChangeService.Diff("DELETE_FILE", "deleted.txt", null, sha("被删"), "被删", ""));
        snapshots.afterApply(owner, binding, intent);
        write("from.txt", "后来新建"); write("deleted.txt", "后来新建"); write("to.txt", "外部替换");
        assertThat(read("to.txt", 0, 1024, null).content()).isEqualTo("批准内容");
        for (String path : List.of("from.txt", "deleted.txt")) assertThatThrownBy(() -> read(path, 0, 1024, null))
                .isInstanceOfSatisfying(LocalProjectAccessException.class, error -> assertThat(error.status()).isEqualTo(404));
        verify(files, never()).mutationSnapshot(any(), anyString());
    }

    @Test void 审批保留只调整显式期限且清理后不能补造缓存() throws Exception {
        write("source.txt", "固定正文"); read("source.txt", 0, 1024, null);
        Instant expiry = time.get().plusSeconds(300); snapshots.retain(owner, binding, expiry);
        time.set(time.get().plusSeconds(100)); snapshots.verify(owner, binding); read("source.txt", 0, 1024, null);
        assertThat(row().expiresAt()).isEqualTo(expiry);
        snapshots.cleanup(binding); assertThat(store.hasAnyCheckpoints()).isFalse();
        assertThatThrownBy(() -> snapshots.verify(owner, binding)).isInstanceOf(LocalProjectAccessException.class);
        verify(files, times(1)).mutationSnapshot(scope.root(), "source.txt");
    }

    @Test void 变更预算在副作用前预检且失败不污染原槽() throws Exception {
        write("old.txt", "安全原版本"); read("old.txt", 0, 1024, null); String before = row().encryptedPayload();
        var oversized = preview(new LocalProjectChangeService.Diff("CREATE_FILE", "huge.txt", null, "", "", "x".repeat(2_097_153)));
        assertThatThrownBy(() -> snapshots.validateApply(owner, binding, oversized)).isInstanceOfSatisfying(LocalProjectAccessException.class,
                error -> { assertThat(error.status()).isEqualTo(422); assertThat(error.errorCode()).isEqualTo("PROJECT_FILE_LIMIT_EXCEEDED"); });
        assertThat(row().encryptedPayload()).isEqualTo(before);
        assertThat(read("old.txt", 0, 1024, null).content()).isEqualTo("安全原版本");
    }

    @Test void 文件个数预算有界且拒绝后保留先前版本() throws Exception {
        for (int i = 0; i < 32; i++) { write("file" + i + ".txt", "value" + i); read("file" + i + ".txt", 0, 1024, null); }
        write("overflow.txt", "不能扩充缓存"); String before = row().encryptedPayload();
        assertThatThrownBy(() -> read("overflow.txt", 0, 1024, null)).isInstanceOf(LocalProjectAccessException.class);
        assertThat(row().encryptedPayload()).isEqualTo(before); assertThat(read("file0.txt", 0, 1024, null).content()).isEqualTo("value0");
    }

    @Test void 显式读取后正文搜索沿用旧版本而不暴露外部新片段() throws Exception {
        write("source.txt", "原版本固定命中");read("source.txt",0,1024,null);
        write("source.txt", "外部更新的片段");
        var original=snapshots.search(owner,scope,"固定命中",true);
        var changed=snapshots.search(owner,scope,"外部更新",true);
        assertThat(original.matches()).singleElement().satisfies(value->{assertThat(value.path()).isEqualTo("source.txt");assertThat(value.excerpt()).contains("原版本固定命中");});
        assertThat(changed.matches()).isEmpty();
        verify(files,times(1)).mutationSnapshot(scope.root(),"source.txt");
    }

    @Test void 搜索首次正文跨审批保留和实例重建仍不读最新磁盘() throws Exception {
        write("source.txt", "审批前搜索结果");
        var first=snapshots.search(owner,scope,"搜索结果",true);
        snapshots.retain(owner,binding,time.get().plusSeconds(300));
        write("source.txt", "不能隐式读入的审批后内容");
        time.set(time.get().plusSeconds(150));
        var restored=make(cipher);restored.verify(owner,binding);
        assertThat(restored.search(owner,scope,"搜索结果",true).matches()).isEqualTo(first.matches());
        assertThat(restored.search(owner,scope,"审批后内容",true).matches()).isEmpty();
        assertThat(restored.read(owner,scope,"source.txt",0,1024,null).content()).isEqualTo("审批前搜索结果");
        verify(files,times(1)).mutationSnapshot(scope.root(),"source.txt");
    }

    @Test void 冻结文件外部删除仍可搜索而批准删除墓碑拒绝后来同名正文() throws Exception {
        write("frozen.txt", "仍然可见的冻结命中");read("frozen.txt",0,1024,null);
        Files.delete(scope.root().directory().resolve("frozen.txt"));
        snapshots.afterApply(owner,binding,preview(new LocalProjectChangeService.Diff("DELETE_FILE","deleted.txt",null,sha("被删"),"被删","")));
        write("deleted.txt", "后来创建的冻结命中");
        var result=snapshots.search(owner,scope,"冻结命中",true);
        assertThat(result.matches()).singleElement().satisfies(value->{assertThat(value.path()).isEqualTo("frozen.txt");assertThat(value.excerpt()).contains("仍然可见").doesNotContain("后来创建");});
        verify(files,never()).mutationSnapshot(scope.root(),"deleted.txt");
    }

    @Test void 搜索文件数预算耗尽显式返回部分且不读取溢出正文() throws Exception {
        for(int i=0;i<32;i++){write("file"+i+".txt","已固定内容"+i);read("file"+i+".txt",0,1024,null);}
        write("overflow.txt","不得泄露的溢出命中");String before=row().encryptedPayload();
        var result=snapshots.search(owner,scope,"溢出命中",true);
        assertThat(result.partial()).isTrue();assertThat(result.skipped()).isGreaterThan(0);assertThat(result.matches()).isEmpty();
        assertThat(row().encryptedPayload()).isEqualTo(before);
        verify(files,never()).mutationSnapshot(scope.root(),"overflow.txt");
    }

    @Test void 搜索累计字节预算耗尽不从实时正文补片段() throws Exception {
        for(String path:List.of("first.txt","second.txt")){write(path,"x".repeat(1_048_576));read(path,0,1024,null);}
        write("overflow.txt","不能补读的命中");
        var result=snapshots.search(owner,scope,"不能补读",true);
        assertThat(result.partial()).isTrue();assertThat(result.matches()).isEmpty();
        verify(files,never()).mutationSnapshot(scope.root(),"overflow.txt");
    }

    @Test void 搜索脱敏后匹配且目录超时保留已冻结结果并标为部分() throws Exception {
        write("source.txt","api-key=synthetic-search-secret\n安全命中");
        var first=snapshots.search(owner,scope,"安全命中",true);
        assertThat(first.matches()).singleElement().satisfies(value->assertThat(value.excerpt()).contains("安全命中","已脱敏").doesNotContain("synthetic-search-secret"));
        assertThat(snapshots.search(owner,scope,"synthetic-search-secret",true).matches()).isEmpty();
        doThrow(new LocalProjectFileException(LocalProjectFileException.Reason.TIMEOUT)).when(files).list(scope.root(),"",null,200);
        var partial=snapshots.search(owner,scope,"安全命中",true);
        assertThat(partial.partial()).isTrue();assertThat(partial.matches()).isEqualTo(first.matches());
        verify(files,times(1)).mutationSnapshot(scope.root(),"source.txt");
        assertThat(row().encryptedPayload()).doesNotContain("synthetic-search-secret","安全命中");
    }

    private LocalProjectReadSnapshots make(ModelCredentialCipher key) { return new LocalProjectReadSnapshots(files, key, json, store, new SensitiveDataRedactor(), access, clock, Duration.ofMinutes(2)); }
    private LocalProjectService.Text read(String path, int offset, int max, String sha) { return snapshots.read(owner, scope, path, offset, max, sha); }
    private void write(String path, String value) throws Exception { Files.writeString(scope.root().directory().resolve(path), value); }
    private RuntimeCheckpoint row() { return store.find(binding.tenantId(), binding.tenantId() + ":" + binding.ownerId(), binding.runId().toString(), "local_project_reads_v1").orElseThrow(); }
    private LocalProjectChangeService.Preview preview(LocalProjectChangeService.Diff... differences) { return new LocalProjectChangeService.Preview(null, List.of(differences)); }
    private static String sha(String value) { return WorkspaceContextResource.digest(value); }
    private static ModelCredentialCipher randomCipher() throws Exception { var generator = KeyGenerator.getInstance("AES"); generator.init(256); return new ModelCredentialCipher(generator.generateKey()); }
}
