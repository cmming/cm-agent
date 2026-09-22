package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 技能逻辑依赖在当前租户环境中的工具映射。
 *
 * @param tenantId 映射所属租户
 * @param skillId 映射所属技能
 * @param logicalKey 技能包声明的逻辑工具键
 * @param toolId 管理员选择的租户工具标识
 * @param updatedBy 最近修改映射的可信主体
 * @param updatedAt 最近修改时间
 */
public record SkillDependencyMapping(
        UUID tenantId,
        UUID skillId,
        String logicalKey,
        UUID toolId,
        String updatedBy,
        Instant updatedAt
) {
    /** 校验映射归属和审计字段；工具授权仍由预检及执行网关单独判断。 */
    public SkillDependencyMapping {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        logicalKey = SkillDependencyResolution.requireLogicalKey(logicalKey);
        Objects.requireNonNull(toolId, "toolId 不能为空");
        updatedBy = requireText(updatedBy, "updatedBy 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
