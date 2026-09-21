package com.cmagent.core.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一份技能版本及其全部文本资源的只读运行视图。
 *
 * @param definition 技能稳定身份和当前状态
 * @param version 本视图固定的不可变版本
 * @param resources 该版本的文本资源
 */
public record SkillVersionView(
        SkillDefinition definition,
        SkillVersion version,
        List<SkillResource> resources
) {

    /** 校验定义、版本和资源的租户及归属键一致性。 */
    public SkillVersionView {
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(version, "version 不能为空");
        resources = List.copyOf(Objects.requireNonNull(resources, "resources 不能为空"));
        if (!definition.tenantId().equals(version.tenantId())
                || !definition.id().equals(version.skillId())) {
            throw new IllegalArgumentException("技能版本不属于当前定义");
        }
        Set<String> paths = new HashSet<>();
        for (SkillResource resource : resources) {
            if (!version.tenantId().equals(resource.tenantId())
                    || !version.skillId().equals(resource.skillId())
                    || !version.id().equals(resource.versionId())) {
                throw new IllegalArgumentException("技能资源不属于当前版本");
            }
            if (!paths.add(resource.path())) {
                throw new IllegalArgumentException("技能资源路径不能重复");
            }
        }
    }
}
