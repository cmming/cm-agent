package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 技能的稳定身份和当前生效状态，具体正文由不可变的 {@link SkillVersion} 保存。
 *
 * @param id 技能稳定标识
 * @param tenantId 技能所属租户
 * @param name 租户内唯一的技能名称
 * @param currentVersionId 当前版本标识
 * @param enabled 是否允许新运行绑定并读取
 * @param accessEpoch 访问纪元，停用时递增以永久撤销旧运行快照
 * @param createdBy 创建主体标识
 * @param updatedBy 最近更新主体标识
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record SkillDefinition(
        UUID id,
        UUID tenantId,
        String name,
        UUID currentVersionId,
        boolean enabled,
        long accessEpoch,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {

    /** 校验技能稳定身份和撤销纪元，避免将不完整状态带入持久化或运行时。 */
    public SkillDefinition {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        name = requireText(name, "name 不能为空");
        Objects.requireNonNull(currentVersionId, "currentVersionId 不能为空");
        createdBy = requireText(createdBy, "createdBy 不能为空");
        updatedBy = requireText(updatedBy, "updatedBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (accessEpoch < 0) {
            throw new IllegalArgumentException("技能访问纪元不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
