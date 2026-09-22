package com.cmagent.server.store;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.domain.SkillReleaseAction;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySkillStoreTest {

    @Test
    void 相同名字按租户隔离且同租户重名拒绝() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        SkillDefinition first = definition(firstTenant, UUID.randomUUID(), "support-guide", Instant.EPOCH);
        SkillDefinition second = definition(secondTenant, UUID.randomUUID(), "support-guide", Instant.EPOCH);

        store.execute(() -> {
            store.definitions().insert(first);
            store.definitions().insert(second);
            return null;
        });

        assertThat(store.definitions().findByName(firstTenant, "support-guide")).contains(first);
        assertThat(store.definitions().findByName(secondTenant, "support-guide")).contains(second);
        assertThatThrownBy(() -> store.execute(() -> {
            store.definitions().insert(definition(firstTenant, UUID.randomUUID(), "support-guide", Instant.EPOCH));
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessage("技能名称已存在");
    }

    @Test
    void 工作单元失败不能发布空快照() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        RunSkillSnapshot snapshot = new RunSkillSnapshot(
                tenant, run, UUID.randomUUID(), 1, List.of(), Instant.EPOCH);

        assertThatThrownBy(() -> store.execute(() -> {
            store.snapshots().insert(snapshot);
            throw new IllegalStateException("模拟严格审计失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.snapshots().find(tenant, run)).isEmpty();
    }

    @Test
    void 空快照成功发布后仍能区分尚未创建快照() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        RunSkillSnapshot snapshot = new RunSkillSnapshot(
                tenant, run, UUID.randomUUID(), 1, List.of(), Instant.EPOCH);

        store.execute(() -> {
            store.snapshots().insert(snapshot);
            return null;
        });

        assertThat(store.snapshots().find(tenant, run)).contains(snapshot);
        assertThat(store.snapshots().find(tenant, UUID.randomUUID())).isEmpty();
    }

    @Test
    void 解绑后重绑使用新绑定标识() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        AgentSkillBinding first = binding(tenant, agent, skill, UUID.randomUUID());
        AgentSkillBinding second = binding(tenant, agent, skill, UUID.randomUUID());

        store.execute(() -> {
            store.bindings().lockAgent(tenant, agent);
            store.bindings().insert(first);
            return null;
        });
        store.execute(() -> {
            store.bindings().lockAgent(tenant, agent);
            assertThat(store.bindings().delete(tenant, agent, skill)).isTrue();
            store.bindings().insert(second);
            return null;
        });

        assertThat(store.bindings().find(tenant, agent, skill)).contains(second);
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void 嵌套工作单元复用暂存状态且异常后清理线程上下文() {
        InMemorySkillStore store = new InMemorySkillStore();
        SkillDefinition nested = definition(UUID.randomUUID(), UUID.randomUUID(), "nested", Instant.EPOCH);
        SkillDefinition recovered = definition(UUID.randomUUID(), UUID.randomUUID(), "recovered", Instant.EPOCH);

        store.execute(() -> {
            store.definitions().insert(nested);
            return store.execute(() -> {
                assertThat(store.definitions().find(nested.tenantId(), nested.id())).contains(nested);
                return null;
            });
        });
        assertThatThrownBy(() -> store.execute(() -> {
            throw new IllegalStateException("失败");
        })).isInstanceOf(IllegalStateException.class);
        store.execute(() -> store.definitions().insert(recovered));

        assertThat(store.definitions().find(nested.tenantId(), nested.id())).contains(nested);
        assertThat(store.definitions().find(recovered.tenantId(), recovered.id())).contains(recovered);
    }

    @Test
    void 并发重复绑定只能发布一个() throws Exception {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> insertBinding(store, start,
                    binding(tenant, agent, skill, UUID.randomUUID())));
            Future<Boolean> second = executor.submit(() -> insertBinding(store, start,
                    binding(tenant, agent, skill, UUID.randomUUID())));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(store.bindings().list(tenant, agent)).hasSize(1);
    }

    @Test
    void 定义和读取记录分页顺序稳定() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        SkillDefinition older = definition(tenant, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "older", Instant.parse("2026-01-01T00:00:00Z"));
        SkillDefinition newer = definition(tenant, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "newer", Instant.parse("2026-01-02T00:00:00Z"));
        UUID runId = UUID.randomUUID();
        SkillLoadRecord first = load(tenant, runId, "call-1", 1, Instant.parse("2026-01-01T00:00:00Z"));
        SkillLoadRecord second = load(tenant, runId, "call-2", 2, Instant.parse("2026-01-02T00:00:00Z"));

        store.execute(() -> {
            store.definitions().insert(older);
            store.definitions().insert(newer);
            store.loads().insert(first);
            store.loads().insert(second);
            return null;
        });

        assertThat(store.definitions().list(tenant, "", null, new ApiPageRequest(0, 1)).items())
                .containsExactly(newer);
        assertThat(store.loads().list(tenant, runId, new ApiPageRequest(0, 10)).items())
                .containsExactly(second, first);
        assertThat(store.loads().listForBudget(tenant, runId)).containsExactly(first, second);
    }

    @Test
    void 停用状态先对事务内读取可见再记录拒绝结果() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        SkillDefinition enabled = new SkillDefinition(
                skillId, tenant, "revocable", versionId, true, 0,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH);
        SkillDefinition disabled = new SkillDefinition(
                skillId, tenant, "revocable", versionId, false, 1,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        SkillLoadRecord denied = new SkillLoadRecord(
                UUID.randomUUID(), tenant, runId, "call-revoked", 1,
                skillId, versionId, "SKILL.md", SkillLoadStatus.DENIED,
                0, 1, ApiErrorCode.SKILL_ACCESS_REVOKED, "error-revoked", Instant.EPOCH.plusSeconds(1));

        store.execute(() -> {
            store.definitions().insert(enabled);
            return null;
        });
        store.execute(() -> {
            store.definitions().lock(tenant, skillId);
            store.definitions().updateEnabled(disabled);
            assertThat(store.definitions().find(tenant, skillId)).contains(disabled);
            store.loads().insert(denied);
            return null;
        });

        assertThat(store.definitions().find(tenant, skillId)).contains(disabled);
        assertThat(store.loads().findByCall(tenant, runId, "call-revoked")).contains(denied);
    }

    @Test
    void 锁方法只能在工作单元内调用且找不到时受控失败() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID skill = UUID.randomUUID();

        assertThatThrownBy(() -> store.definitions().lock(tenant, skill))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("锁方法只能在技能工作单元内调用");
        assertThatThrownBy(() -> store.execute(() -> store.definitions().lock(tenant, skill)))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("技能不存在");
    }

    @Test
    void 发布预检和试运行仓储共享原子工作单元() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        SkillDependency dependency = new SkillDependency(
                tenant, skill, candidate, "search", true, "搜索工具", 0);
        SkillDependencyMapping mapping = new SkillDependencyMapping(
                tenant, skill, "search", UUID.randomUUID(), "tester", Instant.EPOCH);
        SkillPreflightCheck check = new SkillPreflightCheck(
                UUID.randomUUID(), tenant, skill, candidate, 0,
                SkillPreflightScope.STRUCTURAL, null,
                SkillPreflightStatus.PASSED, "tester", Instant.EPOCH);
        SkillPreflightItem item = new SkillPreflightItem(
                check.id(), null, "search", true, mapping.toolId(),
                com.cmagent.core.domain.SkillPreflightItemStatus.READY, null, "已就绪", null);
        SkillRelease release = new SkillRelease(
                UUID.randomUUID(), tenant, skill, 1, SkillReleaseAction.PUBLISH,
                published, candidate, check.id(), UUID.randomUUID(), "tester", Instant.EPOCH);
        SkillTrial trial = new SkillTrial(
                UUID.randomUUID(), tenant, skill, candidate, UUID.randomUUID(), 0,
                SkillTrialStatus.PASSED, true, "tester", Instant.EPOCH, Instant.EPOCH);

        store.execute(() -> {
            store.dependencies().insertAll(List.of(dependency));
            store.mappings().save(mapping);
            store.preflights().save(check, List.of(item));
            store.releases().insert(release);
            store.trials().insert(trial);
            return null;
        });

        assertThat(store.dependencies().list(tenant, skill, candidate)).containsExactly(dependency);
        assertThat(store.mappings().list(tenant, skill)).containsExactly(mapping);
        assertThat(store.preflights().find(tenant, check.id())).contains(check);
        assertThat(store.preflights().listItems(tenant, check.id())).containsExactly(item);
        assertThat(store.releases().find(tenant, release.id())).contains(release);
        assertThat(store.trials().find(tenant, trial.runId())).contains(trial);

        SkillPreflightCheck rollbackCheck = new SkillPreflightCheck(
                UUID.randomUUID(), tenant, skill, candidate, 0,
                SkillPreflightScope.STRUCTURAL, null,
                SkillPreflightStatus.PASSED, "tester", Instant.EPOCH);
        SkillPreflightItem rollbackItem = new SkillPreflightItem(
                rollbackCheck.id(), null, "search", true, mapping.toolId(),
                com.cmagent.core.domain.SkillPreflightItemStatus.READY, null, "已就绪", null);
        SkillRelease rollbackRelease = new SkillRelease(
                UUID.randomUUID(), tenant, skill, 2, SkillReleaseAction.PUBLISH,
                candidate, published, rollbackCheck.id(), UUID.randomUUID(), "tester", Instant.EPOCH);
        SkillTrial rollbackTrial = new SkillTrial(
                UUID.randomUUID(), tenant, skill, candidate, UUID.randomUUID(), 0,
                SkillTrialStatus.RUNNING, false, "tester", Instant.EPOCH, Instant.EPOCH);
        assertThatThrownBy(() -> store.execute(() -> {
            store.releases().insert(rollbackRelease);
            store.preflights().save(rollbackCheck, List.of(rollbackItem));
            store.trials().insert(rollbackTrial);
            throw new IllegalStateException("回滚");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.releases().find(tenant, rollbackRelease.id())).isEmpty();
        assertThat(store.preflights().find(tenant, rollbackCheck.id())).isEmpty();
        assertThat(store.preflights().listItems(tenant, rollbackCheck.id())).isEmpty();
        assertThat(store.trials().find(tenant, rollbackTrial.runId())).isEmpty();
    }

    @Test
    void 新增技能仓储执行唯一性和乐观控制约束() {
        InMemorySkillStore store = new InMemorySkillStore();
        UUID tenant = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        SkillDefinition definition = new SkillDefinition(
                skill, tenant, "release-skill", candidate, published, true, 0, 3,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH);
        SkillDependencyMapping original = new SkillDependencyMapping(
                tenant, skill, "search", UUID.randomUUID(), "tester", Instant.EPOCH);
        SkillPreflightCheck check = new SkillPreflightCheck(
                UUID.randomUUID(), tenant, skill, candidate, 0,
                SkillPreflightScope.STRUCTURAL, null,
                SkillPreflightStatus.PASSED, "tester", Instant.EPOCH);
        SkillTrial trial = new SkillTrial(
                UUID.randomUUID(), tenant, skill, candidate, UUID.randomUUID(), 0,
                SkillTrialStatus.RUNNING, false, "tester", Instant.EPOCH, Instant.EPOCH);

        store.execute(() -> {
            store.definitions().insert(definition);
            store.preflights().save(check, List.of());
            store.trials().insert(trial);
            return null;
        });

        SkillRelease baseline = new SkillRelease(
                UUID.randomUUID(), tenant, skill, 1, SkillReleaseAction.BASELINE,
                null, published, null, null, "tester", Instant.EPOCH);
        store.execute(() -> store.releases().insert(baseline));
        assertThat(store.releases().list(tenant, skill)).containsExactly(baseline);
        assertThatThrownBy(() -> store.execute(() -> store.releases().insert(
                new SkillRelease(UUID.randomUUID(), tenant, skill, 1, SkillReleaseAction.BASELINE,
                        null, published, null, null, "tester", Instant.EPOCH))))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> store.execute(() -> store.trials().insert(trial)))
                .isInstanceOf(IllegalStateException.class);

        store.execute(() -> {
            assertThat(store.definitions().updatePointers(definition, candidate, published)).isTrue();
            assertThat(store.definitions().updatePointers(definition, null, published)).isFalse();
            assertThat(store.definitions().updateDependencyMappingRevision(definition, 2)).isFalse();
            assertThat(store.definitions().updateDependencyMappingRevision(definition, 3)).isTrue();
            return null;
        });
        assertThat(store.definitions().find(tenant, skill).orElseThrow().dependencyMappingRevision())
                .isEqualTo(3);

        store.execute(() -> {
            assertThat(store.mappings().save(original)).isEqualTo(original);
            SkillDependencyMapping replaced = new SkillDependencyMapping(
                    tenant, skill, "search", UUID.randomUUID(), "editor", Instant.EPOCH.plusSeconds(1));
            assertThat(store.mappings().save(replaced)).isEqualTo(replaced);
            assertThat(store.mappings().find(tenant, skill, "search")).contains(replaced);
            return null;
        });
        assertThat(store.execute(() -> store.mappings().delete(tenant, skill, "search"))).isTrue();
        assertThat(store.mappings().find(tenant, skill, "search")).isEmpty();

        SkillTrial next = new SkillTrial(
                trial.runId(), tenant, skill, candidate, trial.agentId(), 0,
                SkillTrialStatus.PASSED, true, "tester", trial.createdAt(), Instant.EPOCH.plusSeconds(1));
        store.execute(() -> {
            assertThat(store.trials().update(next, SkillTrialStatus.RUNNING)).isTrue();
            assertThat(store.trials().update(next, SkillTrialStatus.RUNNING)).isFalse();
            return null;
        });
    }

    private static boolean insertBinding(
            InMemorySkillStore store,
            CountDownLatch start,
            AgentSkillBinding binding
    ) throws InterruptedException {
        start.await();
        try {
            store.execute(() -> {
                store.bindings().lockAgent(binding.tenantId(), binding.agentId());
                store.bindings().insert(binding);
                return null;
            });
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static SkillDefinition definition(
            UUID tenant,
            UUID id,
            String name,
            Instant updatedAt
    ) {
        return new SkillDefinition(
                id, tenant, name, UUID.randomUUID(), false, 0,
                "tester", "tester", Instant.EPOCH, updatedAt);
    }

    private static SkillDependency dependency(UUID tenant, UUID skill, UUID version, String key) {
        return new SkillDependency(tenant, skill, version, key, true, "工具", 0);
    }

    private static AgentSkillBinding binding(UUID tenant, UUID agent, UUID skill, UUID id) {
        return new AgentSkillBinding(id, tenant, agent, skill, "tester", Instant.EPOCH);
    }

    private static SkillLoadRecord load(
            UUID tenant,
            UUID run,
            String modelCallId,
            int attempt,
            Instant createdAt
    ) {
        return new SkillLoadRecord(
                UUID.randomUUID(), tenant, run, modelCallId, attempt,
                UUID.randomUUID(), UUID.randomUUID(), "SKILL.md", SkillLoadStatus.SUCCEEDED,
                4, 1, null, "", createdAt);
    }
}
