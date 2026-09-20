package com.cmagent.core.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 交给运行时的技能快照和已经固定版本的正文视图。
 *
 * @param snapshot Run 的权威技能快照
 * @param versions 与快照逐项对应的不可变版本视图
 */
public record SkillRuntimeBundle(
        RunSkillSnapshot snapshot,
        List<SkillVersionView> versions
) {

    /** 校验运行租户以及技能、版本对应关系，禁止运行时回退到目录当前版本。 */
    public SkillRuntimeBundle {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions 不能为空"));
        Map<UUID, UUID> expectedVersions = new HashMap<>();
        snapshot.skills().forEach(reference -> expectedVersions.put(reference.skillId(), reference.versionId()));
        if (versions.size() != expectedVersions.size()) {
            throw new IllegalArgumentException("技能版本集合与运行快照不一致");
        }
        for (SkillVersionView view : versions) {
            if (!snapshot.tenantId().equals(view.definition().tenantId())) {
                throw new IllegalArgumentException("技能版本不属于运行租户");
            }
            UUID expectedVersionId = expectedVersions.get(view.definition().id());
            if (expectedVersionId == null || !expectedVersionId.equals(view.version().id())) {
                throw new IllegalArgumentException("技能版本集合与运行快照不一致");
            }
        }
    }
}
