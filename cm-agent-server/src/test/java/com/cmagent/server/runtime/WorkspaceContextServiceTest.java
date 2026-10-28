package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.*;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.cmagent.server.store.InMemoryRuntimeCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 合成私有目录、固定时钟和加密仓储验证；不读取开发者资料或访问模型。 */
@ExtendWith(OutputCaptureExtension.class)
class WorkspaceContextServiceTest {
    @TempDir Path root;
    private final UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID();
    private final PrincipalRef owner = new PrincipalRef(tenant, "owner", "测试主体", Set.of("agent:run"));
    private final RunRecord run = RunRecord.create(UUID.randomUUID(), tenant, agent, owner.principalId(), "输入", Instant.now());
    private final InMemoryRuntimeCheckpointRepository rows = spy(new InMemoryRuntimeCheckpointRepository());
    private final AuditAppender audit = mock(AuditAppender.class);
    private final WorkspaceContextProperties policy = new WorkspaceContextProperties();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ModelCredentialCipher cipher = new ModelCredentialCipher(new SecretKeySpec(new java.security.SecureRandom().generateSeed(32), "AES"));
    private WorkspaceContextService service;
    private Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void prepare() throws Exception {
        privateDirectory(root);
        Files.createDirectory(root.resolve("reference"));
        Files.writeString(root.resolve("reference/guide.md"), "参考资料_CANARY_甲");
        policy.setEnabled(true); policy.setRootDirectory(root.toString());
        policy.setBindings(List.of(new WorkspaceContextProperties.Binding(tenant, agent, "reference", List.of("guide.md"))));
        service = create(clock);
    }
    private WorkspaceContextService create(Clock time) {
        // 单测保护源采用合成兄弟目录，另有测试验证真实保护拒绝；生产构造器不接受此覆盖。
        return new WorkspaceContextService(policy, rows, cipher, mapper, audit, time, Duration.ofMinutes(15),
                new WorkspaceContextFiles(List.of(root.resolveSibling("禁止应用"), root.resolveSibling("禁止主目录"))));
    }

    @Test void 默认关闭和未映射不读取资料或创建状态() {
        policy.setEnabled(false);
        assertThat(service.prepare(owner, run, false)).isNull();
        verifyNoInteractions(rows, audit);
        policy.setEnabled(true); policy.setBindings(List.of());
        assertThat(service.prepare(owner, run, false)).isNull();
        assertThat(rows.hasAnyCheckpoints()).isFalse();
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void Windows权限基础设施故障响应和日志保持同号脱敏(CapturedOutput output) {
        var reader = new WorkspaceContextFiles(List.of(root.resolveSibling("禁止根")), new WindowsWorkspaceAclPolicy((p,d) -> {
            throw new java.io.IOException("secret=不得泄露 " + root + " http://internal.invalid");
        }));
        var failed = new WorkspaceContextService(policy, rows, cipher, mapper, audit, clock, Duration.ofMinutes(15), reader);
        code(() -> failed.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE);
        assertThat(output.getAll()).contains("errorId=" + run.id(), "WORKSPACE_CONTEXT_UNAVAILABLE")
                .doesNotContain("不得泄露", root.toString(), "http://internal.invalid");
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void Windows原生组件失败显示具体受控原因及稳定分类(CapturedOutput output) {
        var reader = new WorkspaceContextFiles(List.of(root.resolveSibling("禁止根")), new WindowsWorkspaceAclPolicy((p,d) -> {
            throw new WindowsWorkspaceAclPolicy.Unavailable(WindowsWorkspaceAclPolicy.Reason.NATIVE_UNAVAILABLE);
        }));
        var failed = new WorkspaceContextService(policy, rows, cipher, mapper, audit, clock, Duration.ofMinutes(15), reader);
        assertThatThrownBy(() -> failed.prepare(owner, run, false)).isInstanceOfSatisfying(WorkspaceContextException.class,e->{
            assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE);
            assertThat(e.getMessage()).contains("JNA运行时依赖");assertThat(e.errorId()).isEqualTo(run.id().toString());
        });
        assertThat(output.getAll()).contains("errorId="+run.id(),"reason=NATIVE_UNAVAILABLE").doesNotContain(root.toString());
    }
    @Test void 首次加密冻结且重建后不读取改写或删除的源文件() throws Exception {
        var snapshot = service.prepare(owner, run, false);
        assertThat(row(WorkspaceContextService.KEY).encryptedPayload()).doesNotContain("CANARY", root.toString());
        Files.writeString(root.resolve("reference/guide.md"), "更新版");
        assertThat(create(clock).prepare(owner, run, true)).isEqualTo(snapshot);
        Files.delete(root.resolve("reference/guide.md"));
        assertThat(create(clock).prepare(owner, run, true)).isEqualTo(snapshot);
        service.cleanup(owner, run);
        assertThat(rows.hasAnyCheckpoints()).isFalse();
    }
    @Test void 缺失原快照不能通过移除映射回退为无资料() {
        service.prepare(owner, run, false);
        rows.delete(tenant, user(), run.id().toString(), WorkspaceContextService.KEY);
        policy.setBindings(List.of());
        code(() -> service.prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
    }
    @Test void 过期快照拒绝恢复且清理当前运行() {
        service.prepare(owner, run, false);
        code(() -> create(Clock.offset(clock, Duration.ofMinutes(16))).prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        service.cleanup(owner, run);
        assertThat(rows.hasAnyCheckpoints()).isFalse();
    }
    @Test void 映射关闭或目录变更撤销旧快照() {
        service.prepare(owner, run, false); policy.setEnabled(false);
        code(() -> service.prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
        policy.setEnabled(true);
        policy.setBindings(List.of(new WorkspaceContextProperties.Binding(tenant, agent, "other", List.of("guide.md"))));
        code(() -> service.prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
    }
    @Test void 密文损坏或不匹配的主体快照不能交给模型() throws Exception {
        var original = service.prepare(owner, run, false);
        var previous = row(WorkspaceContextService.KEY);
        rows.save(new RuntimeCheckpoint(previous.id(), tenant, user(), run.id().toString(), previous.stateKey(), previous.stateType(), false,
                "v1:broken", previous.expiresAt(), previous.createdAt(), previous.updatedAt()));
        code(() -> service.prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        var foreign = new WorkspaceContextSnapshot(original.tenantId(), original.agentId(), original.runId(), "other",
                original.mappingDigest(), original.resources(), original.sha256(), original.createdAt());
        rows.save(new RuntimeCheckpoint(previous.id(), tenant, user(), run.id().toString(), previous.stateKey(), previous.stateType(), false,
                cipher.encrypt(mapper.writeValueAsString(foreign)), previous.expiresAt(), previous.createdAt(), previous.updatedAt()));
        code(() -> service.prepare(owner, run, true), ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
    }
    @Test void 跨租户和主体不能读取或清理检查点() {
        service.prepare(owner, run, false);
        assertThatThrownBy(() -> service.prepare(new PrincipalRef(UUID.randomUUID(), "owner", "外租户", Set.of()), run, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cleanup(new PrincipalRef(tenant, "other", "其他主体", Set.of()), run)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows.hasAnyCheckpoints()).isTrue();
    }
    @Test void 实际字节和累计预算均拒绝超限() throws Exception {
        policy.setMaxFileBytes(16); policy.setMaxTotalBytes(16);
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_LIMIT_EXCEEDED);
        policy.setMaxFileBytes(32); policy.setMaxTotalBytes(32);
        Files.writeString(root.resolve("reference/guide.md"), "x".repeat(20));
        Files.writeString(root.resolve("reference/more.txt"), "y".repeat(20));
        policy.setBindings(List.of(new WorkspaceContextProperties.Binding(tenant, agent, "reference", List.of("guide.md", "more.txt"))));
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_LIMIT_EXCEEDED);
    }
    @Test void 缺失和非法UTF8给出不同安全原因(CapturedOutput output) throws Exception {
        Files.delete(root.resolve("reference/guide.md"));
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE);
        Files.write(root.resolve("reference/guide.md"), new byte[]{(byte) 0xff, (byte) 0xfe});
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_INVALID);
        assertThat(output.getAll()).contains("errorId=" + run.id(), "WORKSPACE_CONTEXT_UNAVAILABLE").doesNotContain(root.toString(), "CANARY");
    }
    @Test void 禁止应用和用户目录及非私有根() {
        var blocked = new WorkspaceContextService(policy, rows, cipher, mapper, audit, clock, Duration.ofMinutes(15), new WorkspaceContextFiles(List.of(root)));
        code(() -> blocked.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_INVALID);
    }
    @Test void 文件和中间目录的符号链接均拒绝() throws Exception {
        var outside = root.resolve("outside.md"); Files.writeString(outside, "越界正文");
        Files.delete(root.resolve("reference/guide.md"));
        try { Files.createSymbolicLink(root.resolve("reference/guide.md"), outside); }
        catch (java.nio.file.FileSystemException denied) { Assumptions.abort("当前本地账号无符号链接权限，Rocky必须执行此合同"); }
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_INVALID);
        Files.delete(root.resolve("reference/guide.md")); Files.delete(root.resolve("reference"));
        Files.createSymbolicLink(root.resolve("reference"), root);
        code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_INVALID);
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void Windows目录联接不能绕过相对资料目录() throws Exception {
        Path target = Files.createDirectory(root.resolve("junction-target"));
        Files.writeString(target.resolve("guide.md"), "联接不应交付的正文");
        Files.delete(root.resolve("reference/guide.md")); Files.delete(root.resolve("reference"));
        // 仅在测试私有根内创建联接；cmd只执行固定mklink创建，删除使用NIO且不递归穿过联接。
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", root.resolve("reference").toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); Assumptions.abort("本地目录联接夹具创建超时"); }
        if (process.exitValue() != 0) Assumptions.abort("当前账号不支持目录联接创建");
        try { code(() -> service.prepare(owner, run, false), ApiErrorCode.WORKSPACE_CONTEXT_INVALID); }
        finally { Files.deleteIfExists(root.resolve("reference")); }
    }
    @Test void 严格审计和清理故障不会降级或泄露诊断(CapturedOutput output) {
        doThrow(new AuditPersistenceException("合成失败", new IllegalStateException("secret=不得泄露 " + root)))
                .when(audit).append(any(), any(), any(), any(), any(), any(), any());
        code(() -> service.prepare(owner, run, false), ApiErrorCode.AUDIT_UNAVAILABLE);
        reset(audit);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret=不得泄露 SQL " + root))
                .when(rows).delete(any(), anyString(), anyString(), anyString());
        code(() -> service.cleanup(owner, run), ApiErrorCode.PERSISTENCE_UNAVAILABLE);
        assertThat(output.getAll()).contains("errorId=" + run.id(), "AUDIT_UNAVAILABLE", "PERSISTENCE_UNAVAILABLE").doesNotContain("不得泄露", root.toString());
    }
    @Test void ASK刷新原版本期限而不重建内容() throws Exception {
        var snapshot = service.prepare(owner, run, false);
        Files.delete(root.resolve("reference/guide.md"));
        create(Clock.offset(clock, Duration.ofMinutes(10))).retain(run, snapshot);
        assertThat(row(WorkspaceContextService.KEY).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(25)));
        assertThat(create(Clock.offset(clock, Duration.ofMinutes(16))).prepare(owner, run, true)).isEqualTo(snapshot);
    }
    @Test void 撤销拒绝审计不可用时保持严格审计失败及原原因(CapturedOutput output) {
        service.prepare(owner, run, false); policy.setEnabled(false);
        doThrow(new AuditPersistenceException("合成失败", new IllegalStateException("secret=不得泄露")))
                .when(audit).appendControlled(any(), any(), eq("WORKSPACE_CONTEXT_FAILURE"), any(), any(), any(), any());
        assertThatThrownBy(() -> service.prepare(owner, run, true)).isInstanceOfSatisfying(WorkspaceContextException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ApiErrorCode.AUDIT_UNAVAILABLE);
            assertThat(failure.getSuppressed()).singleElement().isInstanceOf(WorkspaceContextException.class);
            assertThat(((WorkspaceContextException) failure.getSuppressed()[0]).code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
        });
        assertThat(output.getAll()).contains("errorId=" + run.id(), "AUDIT_UNAVAILABLE").doesNotContain("不得泄露", root.toString());
    }
    @Test void 快照不能附着到其他Run或扩展授权() {
        var snapshot = service.prepare(owner, run, false);
        var another = new WorkspaceContextSnapshot(tenant, agent, UUID.randomUUID(), "owner", snapshot.mappingDigest(), snapshot.resources(), snapshot.sha256(), clock.instant());
        var definition = new AgentDefinition(agent, tenant, "测试", "", "", UUID.randomUUID(), "fixture", 0.1, 2, true, List.of(), "owner", "owner");
        var model = new ModelConfig(definition.modelProviderId(), tenant, ModelProviderType.OPENAI_COMPATIBLE, "测试", "http://127.0.0.1:1/v1", "fixture", true);
        assertThatThrownBy(() -> new AgentRunRequest(run.id(), tenant, definition, model, owner, "输入", List.of(), null, List.of(), Map.of(),
                new com.cmagent.core.runtime.RunCancellation(), another)).isInstanceOf(IllegalArgumentException.class);
    }
    private RuntimeCheckpoint row(String key) { return rows.find(tenant, user(), run.id().toString(), key).orElseThrow(); }
    private String user() { return tenant + ":owner"; }
    private void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ApiErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(WorkspaceContextException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code); assertThat(failure.errorId()).isEqualTo(run.id().toString());
            assertThat(failure.getMessage()).doesNotContain(root.toString(), "CANARY", "Exception", "SQL");
        });
    }
    /** 创建者主动为合成测试目录设置私有权限；生产读取不会替部署者更改权限。 */
    static void privateDirectory(Path path) throws Exception {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
        else Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
    }
}
