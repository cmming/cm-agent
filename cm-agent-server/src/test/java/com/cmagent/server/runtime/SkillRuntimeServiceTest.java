package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.store.InMemorySkillStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRuntimeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(tenant, "runner", "运行者", Set.of("agent:run"));
    private final InMemorySkillStore store = new InMemorySkillStore();
    private final SkillProperties properties = enabledProperties();
    private final SkillRuntimeService service = new SkillRuntimeService(
            store.definitions(), store.versions(), store.resources(), store.bindings(), store.snapshots(),
            store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 空技能集合也保存快照且重复准备不会覆盖() {
        RunRecord run = run();
        var first = service.prepare(principal, run);
        var second = service.prepare(principal, run);

        assertThat(first.snapshot().skills()).isEmpty();
        assertThat(second.snapshot()).isEqualTo(first.snapshot());
        assertThat(store.snapshots().find(tenant, run.id())).contains(first.snapshot());
    }

    @Test
    void 缺失快照不能自动重建为最新版本() {
        RunSkillSnapshotRepository snapshots = mock(RunSkillSnapshotRepository.class);
        UUID runId = UUID.randomUUID();
        when(snapshots.find(tenant, runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> SkillRuntimeService.requireSnapshot(snapshots, tenant, runId))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE));
        verify(snapshots, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 更新技能不会替换旧快照版本而停用再启用永久撤销旧快照() {
        Fixture fixture = insertEnabledSkill();
        RunRecord run = run();
        var prepared = service.prepare(principal, run);

        UUID nextVersionId = UUID.randomUUID();
        store.execute(() -> {
            SkillVersion nextVersion = version(fixture.skillId(), nextVersionId, 2, "第二版");
            store.versions().insert(nextVersion);
            SkillDefinition current = store.definitions().lock(tenant, fixture.skillId());
            store.definitions().updateCurrent(new SkillDefinition(
                    current.id(), current.tenantId(), current.name(), nextVersionId, true, current.accessEpoch(),
                    current.createdBy(), "editor", current.createdAt(), NOW.plusSeconds(1)), fixture.versionId());
            return null;
        });
        assertThat(service.restore(principal, run).versions().getFirst().version().id())
                .isEqualTo(fixture.versionId());

        store.execute(() -> {
            SkillDefinition current = store.definitions().lock(tenant, fixture.skillId());
            store.definitions().updateEnabled(new SkillDefinition(
                    current.id(), current.tenantId(), current.name(), current.currentVersionId(), true,
                    current.accessEpoch() + 1, current.createdBy(), "editor", current.createdAt(), NOW.plusSeconds(2)));
            return null;
        });
        assertThatThrownBy(() -> service.restore(principal, run))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        assertThat(prepared.snapshot().skills().getFirst().versionId()).isEqualTo(fixture.versionId());
    }

    private Fixture insertEnabledSkill() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        store.execute(() -> {
            store.definitions().insert(new SkillDefinition(skillId, tenant, "support", versionId, true,
                    0, "creator", "creator", NOW, NOW));
            store.versions().insert(version(skillId, versionId, 1, "第一版"));
            store.bindings().insert(new AgentSkillBinding(bindingId, tenant, agent, skillId, "creator", NOW));
            return null;
        });
        return new Fixture(skillId, versionId);
    }

    private SkillVersion version(UUID skillId, UUID versionId, int number, String content) {
        return new SkillVersion(versionId, tenant, skillId, number, "测试技能", Map.of(), content,
                "a".repeat(64), "creator", NOW);
    }

    private RunRecord run() {
        return RunRecord.create(UUID.randomUUID(), tenant, agent, principal.principalId(), "测试", NOW);
    }

    private static SkillProperties enabledProperties() {
        SkillProperties result = new SkillProperties();
        result.setEnabled(true);
        return result;
    }

    private record Fixture(UUID skillId, UUID versionId) {
    }
}
