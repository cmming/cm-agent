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
            store.loads(), store, audit, properties, Clock.fixed(NOW, ZoneOffset.UTC));

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
