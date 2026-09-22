package com.cmagent.core.domain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 交给运行时的技能快照、固定版本正文和依赖映射。
 *
 * @param snapshot Run 的权威技能快照
 * @param versions 与快照逐项对应的不可变版本视图
 * @param dependencyResolutions 按技能标识分组的固定依赖解析
 */
public record SkillRuntimeBundle(
        RunSkillSnapshot snapshot,
        List<SkillVersionView> versions,
        Map<UUID, List<SkillDependencyResolution>> dependencyResolutions
) {
    /** 校验租户、版本和依赖分组对应关系，禁止运行时回退到可变目录或映射。 */
    public SkillRuntimeBundle {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions 不能为空"));
        dependencyResolutions = freeze(dependencyResolutions);
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
        if (!expectedVersions.keySet().containsAll(dependencyResolutions.keySet())) {
            throw new IllegalArgumentException("技能依赖解析包含快照外技能");
        }
    }

    /** 兼容旧运行时组装方式，依赖解析为空。 */
    public SkillRuntimeBundle(RunSkillSnapshot snapshot, List<SkillVersionView> versions) {
        this(snapshot, versions, Map.of());
    }

    private static Map<UUID, List<SkillDependencyResolution>> freeze(
            Map<UUID, List<SkillDependencyResolution>> source
    ) {
        Objects.requireNonNull(source, "dependencyResolutions 不能为空");
        Map<UUID, List<SkillDependencyResolution>> copy = new LinkedHashMap<>();
        source.forEach((skillId, values) -> copy.put(
                Objects.requireNonNull(skillId, "依赖解析技能标识不能为空"),
                List.copyOf(Objects.requireNonNull(values, "依赖解析集合不能为空"))));
        return Map.copyOf(copy);
    }
}
