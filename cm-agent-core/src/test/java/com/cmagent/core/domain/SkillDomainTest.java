package com.cmagent.core.domain;

import com.cmagent.api.ApiErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillDomainTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SKILL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID BINDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

    @Test
    void 新技能允许候选和发布指针同时为空但拒绝负映射修订() {
        SkillDefinition definition = new SkillDefinition(
                SKILL_ID, TENANT_ID, "support-guide", null, null, false, 0, 0,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH);

        assertThat(definition.candidateVersionId()).isNull();
        assertThat(definition.publishedVersionId()).isNull();
        assertThatThrownBy(() -> new SkillDefinition(
                SKILL_ID, TENANT_ID, "support-guide", null, null, false, 0, -1,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能依赖映射修订不能为负数");
    }

    @Test
    void 绑定策略约束固定版本字段() {
        Instant now = Instant.EPOCH;

        assertThatThrownBy(() -> new AgentSkillBinding(
                BINDING_ID, TENANT_ID, UUID.randomUUID(), SKILL_ID,
                SkillBindingMode.PINNED, null, 0, "tester", "tester", now, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("固定版本不能为空");
        assertThatThrownBy(() -> new AgentSkillBinding(
                BINDING_ID, TENANT_ID, UUID.randomUUID(), SKILL_ID,
                SkillBindingMode.FOLLOW_PUBLISHED, VERSION_ID, 0, "tester", "tester", now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("跟随发布的绑定不能携带固定版本");
    }

    @Test
    void 试运行快照保存授权来源并拒绝重复依赖解析() {
        UUID trialRunId = UUID.randomUUID();
        SkillSnapshotRef reference = new SkillSnapshotRef(
                SKILL_ID, VERSION_ID, SkillSnapshotOrigin.TRIAL, trialRunId, 0,
                List.of(new SkillDependencyResolution("search", UUID.randomUUID(), true)));

        assertThat(reference.origin()).isEqualTo(SkillSnapshotOrigin.TRIAL);
        assertThat(reference.authorizationId()).isEqualTo(trialRunId);
        assertThatThrownBy(() -> new SkillSnapshotRef(
                SKILL_ID, VERSION_ID, SkillSnapshotOrigin.TRIAL, trialRunId, 0,
                List.of(
                        new SkillDependencyResolution("search", UUID.randomUUID(), true),
                        new SkillDependencyResolution("search", UUID.randomUUID(), false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能依赖解析键不能重复");
    }

    @Test
    void 发布事实按动作约束预检和试运行依据() {
        UUID preflightId = UUID.randomUUID();
        UUID trialRunId = UUID.randomUUID();
        Instant now = Instant.EPOCH;

        SkillRelease publish = new SkillRelease(
                UUID.randomUUID(), TENANT_ID, SKILL_ID, 2, SkillReleaseAction.PUBLISH,
                UUID.randomUUID(), VERSION_ID, preflightId, trialRunId, "tester", now);
        assertThat(publish.preflightId()).isEqualTo(preflightId);
        assertThatThrownBy(() -> new SkillRelease(
                UUID.randomUUID(), TENANT_ID, SKILL_ID, 2, SkillReleaseAction.PUBLISH,
                UUID.randomUUID(), VERSION_ID, null, trialRunId, "tester", now))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("发布预检标识不能为空");
        assertThatThrownBy(() -> new SkillRelease(
                UUID.randomUUID(), TENANT_ID, SKILL_ID, 2, SkillReleaseAction.ROLLBACK,
                UUID.randomUUID(), VERSION_ID, preflightId, trialRunId, "tester", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("回滚不能引用候选试运行");
        assertThatThrownBy(() -> new SkillRelease(
                UUID.randomUUID(), TENANT_ID, SKILL_ID, 1, SkillReleaseAction.BASELINE,
                null, VERSION_ID, preflightId, null, "tester", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("基线发布不能引用预检或试运行");
    }

    @Test
    void 空快照有效且构造后不能被外部集合修改() {
        List<SkillSnapshotRef> refs = new ArrayList<>();
        RunSkillSnapshot snapshot = new RunSkillSnapshot(
                TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), 1, refs, Instant.EPOCH);

        refs.add(new SkillSnapshotRef(SKILL_ID, VERSION_ID, BINDING_ID, 0));

        assertThat(snapshot.skills()).isEmpty();
        assertThatThrownBy(() -> snapshot.skills().add(refs.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 快照拒绝重复技能和负纪元() {
        SkillSnapshotRef reference = new SkillSnapshotRef(SKILL_ID, VERSION_ID, BINDING_ID, 0);

        assertThatThrownBy(() -> new RunSkillSnapshot(
                TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), 1,
                List.of(reference, new SkillSnapshotRef(
                        SKILL_ID, UUID.randomUUID(), UUID.randomUUID(), 1)), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("运行技能不能重复");
        assertThatThrownBy(() -> new SkillSnapshotRef(SKILL_ID, VERSION_ID, BINDING_ID, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能访问纪元不能为负数");
    }

    @Test
    void 版本元数据递归冻结且版本号从一开始() {
        List<Object> labels = new ArrayList<>(List.of("客服"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("labels", labels);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", nested);

        SkillVersion version = version(TENANT_ID, SKILL_ID, VERSION_ID, metadata);
        labels.add("故障处理");
        nested.put("extra", true);

        assertThat(version.metadata()).containsOnlyKeys("profile");
        List<?> frozenLabels = (List<?>) ((Map<?, ?>) version.metadata().get("profile")).get("labels");
        assertThat(frozenLabels).hasSize(1);
        assertThat(frozenLabels.getFirst()).isEqualTo("客服");
        assertThatThrownBy(() -> version.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SkillVersion(
                VERSION_ID, TENANT_ID, SKILL_ID, 0, "说明", Map.of(),
                "正文", "a".repeat(64), "tester", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能版本号必须从 1 开始");
    }

    @Test
    void 资源校验Utf8长度和版本归属() {
        String content = "中文资料";
        int utf8Length = content.getBytes(StandardCharsets.UTF_8).length;
        SkillResource resource = new SkillResource(
                TENANT_ID, SKILL_ID, VERSION_ID, "references/guide.md", "text/markdown",
                content, utf8Length, "b".repeat(64));
        SkillVersionView view = new SkillVersionView(
                definition(TENANT_ID, SKILL_ID, VERSION_ID),
                version(TENANT_ID, SKILL_ID, VERSION_ID, Map.of()), List.of(resource));

        assertThat(view.resources()).containsExactly(resource);
        assertThatThrownBy(() -> new SkillResource(
                TENANT_ID, SKILL_ID, VERSION_ID, "references/guide.md", "text/markdown",
                content, content.length(), "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能资源字节数与 UTF-8 正文不一致");
        assertThatThrownBy(() -> new SkillVersionView(
                definition(TENANT_ID, SKILL_ID, VERSION_ID),
                version(TENANT_ID, SKILL_ID, VERSION_ID, Map.of()),
                List.of(new SkillResource(TENANT_ID, SKILL_ID, UUID.randomUUID(),
                        "guide.md", "text/markdown", "x", 1, "c".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能资源不属于当前版本");
    }

    @Test
    void 运行时集合冻结且拒绝跨租户版本() {
        RunSkillSnapshot snapshot = new RunSkillSnapshot(
                TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), 1,
                List.of(new SkillSnapshotRef(SKILL_ID, VERSION_ID, BINDING_ID, 0)), Instant.EPOCH);
        List<SkillVersionView> versions = new ArrayList<>(List.of(new SkillVersionView(
                definition(TENANT_ID, SKILL_ID, VERSION_ID),
                version(TENANT_ID, SKILL_ID, VERSION_ID, Map.of()), List.of())));
        SkillRuntimeBundle bundle = new SkillRuntimeBundle(snapshot, versions);
        versions.clear();

        assertThat(bundle.versions()).hasSize(1);
        UUID anotherTenantId = UUID.randomUUID();
        assertThatThrownBy(() -> new SkillRuntimeBundle(snapshot, List.of(new SkillVersionView(
                definition(anotherTenantId, SKILL_ID, VERSION_ID),
                version(anotherTenantId, SKILL_ID, VERSION_ID, Map.of()), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("技能版本不属于运行租户");
    }

    @Test
    void 读取记录区分成功失败和拒绝字段() {
        SkillLoadRecord succeeded = new SkillLoadRecord(
                UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), "call-1", 1,
                SKILL_ID, VERSION_ID, "SKILL.md", SkillLoadStatus.SUCCEEDED,
                8, 12, null, "", Instant.EPOCH);

        assertThat(succeeded.status()).isEqualTo(SkillLoadStatus.SUCCEEDED);
        assertThatThrownBy(() -> new SkillLoadRecord(
                UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), "call-2", 1,
                SKILL_ID, VERSION_ID, "SKILL.md", SkillLoadStatus.DENIED,
                1, 1, ApiErrorCode.SKILL_ACCESS_REVOKED, "error-1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未成功的技能读取不能记录已交付字节");
    }

    private static SkillDefinition definition(UUID tenantId, UUID skillId, UUID versionId) {
        return new SkillDefinition(
                skillId, tenantId, "support-guide", versionId, true, 0,
                "tester", "tester", Instant.EPOCH, Instant.EPOCH);
    }

    private static SkillVersion version(
            UUID tenantId,
            UUID skillId,
            UUID versionId,
            Map<String, Object> metadata
    ) {
        return new SkillVersion(
                versionId, tenantId, skillId, 1, "服务故障排查", metadata,
                "读取 references/guide.md", "a".repeat(64), "tester", Instant.EPOCH);
    }
}
