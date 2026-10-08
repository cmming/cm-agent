package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillSnapshotRef;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.store.InMemorySkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class GovernedSkillAccessServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID bindingId = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(tenant, "runner", "运行者", Set.of("agent:run"));
    private final RunRecord run = RunRecord.create(runId, tenant, agent, principal.principalId(), "测试", NOW);
    private final InMemorySkillStore store = new InMemorySkillStore();
    private final RunRepository runs = mock(RunRepository.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final SkillProperties properties = enabledProperties();
    private final GovernedSkillAccessService service = new GovernedSkillAccessService(
            runs, store.snapshots(), store.definitions(), store.versions(), store.resources(), store.bindings(),
            store.trials(), store.loads(), store, audit, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(runs.findByTenantAndAgentAndId(tenant, agent, runId)).thenReturn(Optional.of(run));
        store.execute(() -> {
            store.definitions().insert(new SkillDefinition(skillId, tenant, "support", versionId, true,
                    0, "creator", "creator", NOW, NOW));
            store.versions().insert(new SkillVersion(versionId, tenant, skillId, 1, "测试技能", Map.of(),
                    "技能正文", "a".repeat(64), "creator", NOW));
            store.bindings().insert(new AgentSkillBinding(bindingId, tenant, agent, skillId, "creator", NOW));
            store.snapshots().insert(new RunSkillSnapshot(tenant, runId, agent, 1,
                    java.util.List.of(new SkillSnapshotRef(skillId, versionId, bindingId, 0)), NOW));
            return null;
        });
    }

    @Test
    void 成功读取提交记录且相同模型调用幂等复用() {
        Supplier<String> loader = mock(Supplier.class);
        when(loader.get()).thenReturn("技能正文");
        SkillReadRequest request = request("call-1");

        var first = service.load(request, loader);
        var second = service.load(request, loader);

        assertThat(first.content()).isEqualTo("技能正文");
        assertThat(second).isEqualTo(first);
        assertThat(store.loads().listForBudget(tenant, runId)).hasSize(1);
        verify(loader, times(1)).get();
    }

    @Test
    void 撤销状态在原生读取前拒绝并留下拒绝记录() {
        store.execute(() -> {
            SkillDefinition current = store.definitions().lock(tenant, skillId);
            store.definitions().updateEnabled(new SkillDefinition(
                    current.id(), tenant, current.name(), current.currentVersionId(), true, 1,
                    current.createdBy(), "editor", current.createdAt(), NOW.plusSeconds(1)));
            return null;
        });
        Supplier<String> loader = mock(Supplier.class);

        assertThatThrownBy(() -> service.load(request("call-revoked"), loader))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        verify(loader, never()).get();
        assertThat(store.loads().listForBudget(tenant, runId).getFirst().status().name()).isEqualTo("DENIED");
    }

    @Test
    void 持久化预算达到上限后不调用原生加载器() {
        properties.setMaxLoadAttempts(1);
        service.load(request("call-first"), () -> "技能正文");
        Supplier<String> loader = mock(Supplier.class);

        assertThatThrownBy(() -> service.load(request("call-over-limit"), loader))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED));
        verify(loader, never()).get();
        assertThat(store.loads().listForBudget(tenant, runId)).hasSize(2);
    }

    @Test
    void 固定脚本执行并拒绝相同调用重复运行() {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        SkillReadRequest request = scriptRequest("script-1");
        when(sandbox.execute(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("输入"))).thenReturn("结果");

        assertThat(execution.execute(request, "输入")).isEqualTo("结果");
        assertThatThrownBy(() -> execution.execute(request, "输入"))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_DUPLICATE));
        verify(sandbox, times(1)).execute(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("输入"));
        verify(audit).append(tenant, principal.principalId(), "SKILL_EXECUTE", "RUN", runId.toString(),
                "SUCCEEDED", "skillId=" + skillId + " versionId=" + versionId + " toolCallId=script-1 技能沙箱执行成功");
        assertThat(store.loads().listForBudget(tenant, runId)).hasSize(1);
        assertThat(store.loads().listForBudget(tenant, runId).getFirst().status())
                .isEqualTo(com.cmagent.core.domain.SkillLoadStatus.SANDBOX_PREPARED);
        assertThat(store.loads().listForBudget(tenant, runId).getFirst().deliveredBytes()).isZero();
    }

    @Test
    void 新默认预算允许较大资源集且多次沙箱准备仍累计扣减() {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        when(sandbox.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("结果");
        // 每份资源都符合默认导入上限；总量超过旧 256 KiB，模拟完整文档技能的沙箱准备。
        String content = "x".repeat(256 * 1024);
        store.execute(() -> {
            store.resources().insertAll(java.util.stream.IntStream.range(0, 5)
                    .mapToObj(index -> new com.cmagent.core.domain.SkillResource(tenant, skillId, versionId,
                            "resources/reference-" + index + ".txt", "text/plain", content, content.length(), "a".repeat(64)))
                    .toList());
            return null;
        });
        int preparedBytes = store.versions().find(tenant, skillId, versionId).orElseThrow()
                .content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + store.resources().list(tenant, skillId, versionId).stream()
                .mapToInt(com.cmagent.core.domain.SkillResource::byteLength).sum();
        assertThat(execution.execute(scriptRequest("large-first"), "")).isEqualTo("结果");
        properties.setMaxLoadedBytes(preparedBytes * 2);
        properties.validate();
        assertThat(execution.execute(scriptRequest("large-second"), "")).isEqualTo("结果");
        var rejected = scriptRequest("large-third");
        assertThatThrownBy(() -> execution.execute(rejected, ""))
                .isInstanceOfSatisfying(SkillAccessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED);
                    assertThat(failure.errorId()).isEqualTo(rejected.attemptId().toString());
                    assertThat(failure.safeMessage()).isEqualTo("本轮技能读取已达上限");
                });
        verify(sandbox, times(2)).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString());
        assertThat(store.loads().listForBudget(tenant, runId)).hasSize(2);
    }

    @Test
    void 沙箱准备的固定资源预算在之后读取时仍被扣减() {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        when(sandbox.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("结果");
        execution.execute(scriptRequest("budget-script"), "");
        properties.setMaxLoadedBytes(20);
        assertThatThrownBy(() -> service.load(request("load-after-script"), () -> "技能正文"))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED));
    }

    @Test
    void 撤销与跨租户或其他主体请求不进入沙箱() {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        PrincipalRef other = new PrincipalRef(UUID.randomUUID(), "other", "其他主体", Set.of("agent:run"));
        SkillReadRequest foreign = new SkillReadRequest(other, agent, runId, "foreign", UUID.randomUUID(), skillId,
                versionId, "scripts/main.py");
        when(runs.findByTenantAndAgentAndId(other.tenantId(), agent, runId)).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> execution.execute(foreign, ""))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        store.execute(() -> {
            SkillDefinition current = store.definitions().lock(tenant, skillId);
            store.definitions().updateEnabled(new SkillDefinition(current.id(), tenant, current.name(),
                    current.currentVersionId(), false, 1, current.createdBy(), "editor", NOW, NOW));
            return null;
        });
        assertThatThrownBy(() -> execution.execute(scriptRequest("revoked"), ""))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        org.mockito.Mockito.verifyNoInteractions(sandbox);
    }

    @Test
    void 严格开始审计失败不执行且诊断脱敏(CapturedOutput output) {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        org.mockito.Mockito.doThrow(new IllegalStateException("api_key=verification-only-value https://internal.example.local"))
                .when(audit).append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("SKILL_EXECUTE"), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("STARTED"), org.mockito.ArgumentMatchers.any());
        SkillReadRequest request = scriptRequest("audit-failure");
        assertThatThrownBy(() -> execution.execute(request, "private-input"))
                .isInstanceOfSatisfying(SkillAccessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE);
                    assertThat(failure.errorId()).isEqualTo(request.attemptId().toString());
                    assertThat(failure.fatal()).isTrue();
                });
        org.mockito.Mockito.verifyNoInteractions(sandbox);
        assertThat(output.getAll()).contains("errorId=" + request.attemptId(), "SKILL_SANDBOX_UNAVAILABLE", "java.lang.RuntimeException")
                .doesNotContain("verification-only-value", "internal.example.local", "private-input");
    }

    @Test
    void 执行期间撤销后丢弃输出并禁止成功审计() {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        when(sandbox.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(ignored -> {
                    store.execute(() -> {
                        SkillDefinition current = store.definitions().lock(tenant, skillId);
                        store.definitions().updateEnabled(new SkillDefinition(current.id(), tenant, current.name(),
                                current.currentVersionId(), false, 1, current.createdBy(), "editor", NOW, NOW));
                        return null;
                    });
                    return "不可交付的输出";
                });
        assertThatThrownBy(() -> execution.execute(scriptRequest("revoke-during-execution"), ""))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        verify(audit, never()).append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("SKILL_EXECUTE"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("SUCCEEDED"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 脚本失败的错误编号与日志审计一致(CapturedOutput output) {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        SkillReadRequest request = scriptRequest("failed-script");
        when(sandbox.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "技能沙箱执行超时",
                        request.attemptId().toString(), true));
        assertThatThrownBy(() -> execution.execute(request, "private-input"))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.errorId()).isEqualTo(request.attemptId().toString()));
        assertThat(output.getAll()).contains("errorId=" + request.attemptId(), "SKILL_SANDBOX_TIMEOUT").doesNotContain("private-input");
        verify(audit).append(tenant, principal.principalId(), "SKILL_EXECUTE", "RUN", runId.toString(), "FAILED",
                "skillId=" + skillId + " versionId=" + versionId + " toolCallId=failed-script SKILL_SANDBOX_TIMEOUT errorId=" + request.attemptId());
    }

    private GovernedSkillAccessService executionService(DockerSkillSandbox sandbox) {
        properties.getSandbox().setEnabled(true);
        store.execute(() -> {
            store.resources().insertAll(java.util.List.of(new com.cmagent.core.domain.SkillResource(tenant, skillId, versionId, "scripts/main.py",
                    "text/plain", "print(1)", 8, "a".repeat(64))));
            return null;
        });
        return new GovernedSkillAccessService(runs, store.snapshots(), store.definitions(), store.versions(), store.resources(),
                store.bindings(), store.trials(), store.loads(), store, audit, properties, Clock.fixed(NOW, ZoneOffset.UTC), sandbox);
    }

    @Test
    void 清理成功后在事务外复核端点策略才允许交付() {
        executionService(mock(DockerSkillSandbox.class));
        var inTransaction = new java.util.concurrent.atomic.AtomicBoolean();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var observed = new com.cmagent.server.service.SkillUnitOfWork() {
            public <T> T execute(Supplier<T> operation) {
                inTransaction.set(true);
                try { return store.execute(operation); } finally { inTransaction.set(false); }
            }
        };
        var backend = mock(com.cmagent.core.runtime.SkillSandboxBackend.class);
        var handle = mock(com.cmagent.core.runtime.SkillSandboxBackend.Execution.class);
        when(backend.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(handle);
        when(handle.execute(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString())).thenReturn("成功输出");
        org.mockito.Mockito.doAnswer(call -> { closed.set(true); return null; }).when(handle).close();
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(closed.get()).isTrue();
            assertThat(inTransaction.get()).isFalse();
            return null;
        }).when(handle).verifyAccess();
        var execution = new GovernedSkillAccessService(runs, store.snapshots(), store.definitions(), store.versions(), store.resources(),
                store.bindings(), store.trials(), store.loads(), observed, audit, properties, Clock.fixed(NOW, ZoneOffset.UTC), backend);
        assertThat(execution.execute(scriptRequest("verify-outside-transaction"), "")).isEqualTo("成功输出");
        verify(handle).verifyAccess();
    }

    @Test
    void 成功收口审计失败时丢弃输出且保持审计错误分类(CapturedOutput output) {
        DockerSkillSandbox sandbox = mock(DockerSkillSandbox.class);
        when(sandbox.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenCallRealMethod();
        GovernedSkillAccessService execution = executionService(sandbox);
        when(sandbox.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("private-output");
        org.mockito.Mockito.doThrow(new com.cmagent.server.audit.AuditPersistenceException("api_key=verification-only-value",
                new IllegalStateException("https://internal.example.local"))).when(audit)
                .append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SKILL_EXECUTE"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SUCCEEDED"),
                        org.mockito.ArgumentMatchers.any());
        var request = scriptRequest("audit-after-execution");
        assertThatThrownBy(() -> execution.execute(request, "private-input"))
                .isInstanceOfSatisfying(SkillAccessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ApiErrorCode.AUDIT_UNAVAILABLE);
                    assertThat(failure.errorId()).isEqualTo(request.attemptId().toString());
                    assertThat(failure.fatal()).isTrue();
                });
        assertThat(output.getAll()).contains("errorId=" + request.attemptId(), "AUDIT_UNAVAILABLE")
                .doesNotContain("verification-only-value", "internal.example.local", "private-output", "private-input");
    }

    private SkillReadRequest scriptRequest(String callId) {
        return new SkillReadRequest(principal, agent, runId, callId, UUID.randomUUID(), skillId, versionId, "scripts/main.py");
    }

    private SkillReadRequest request(String callId) {
        return new SkillReadRequest(principal, agent, runId, callId, UUID.randomUUID(),
                skillId, versionId, "SKILL.md");
    }

    private static SkillProperties enabledProperties() {
        SkillProperties result = new SkillProperties();
        result.setEnabled(true);
        return result;
    }
}
