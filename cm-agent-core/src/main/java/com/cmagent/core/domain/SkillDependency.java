package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 技能版本声明的不可变逻辑工具依赖。
 *
 * @param tenantId 依赖所属租户
 * @param skillId 依赖所属技能
 * @param versionId 依赖所属不可变版本
 * @param logicalKey 技能包内稳定的逻辑工具键
 * @param required 是否为阻断运行和发布的必需依赖
 * @param description 面向管理员的依赖用途说明
 * @param position 技能包声明顺序，从零开始
 */
public record SkillDependency(
        UUID tenantId,
        UUID skillId,
        UUID versionId,
        String logicalKey,
        boolean required,
        String description,
        int position
) {
    /** 校验不可变版本归属、逻辑键格式和稳定声明顺序。 */
    public SkillDependency {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        logicalKey = SkillDependencyResolution.requireLogicalKey(logicalKey);
        description = description == null ? "" : description.strip();
        if (position < 0) {
            throw new IllegalArgumentException("依赖位置不能为负数");
        }
    }
}
