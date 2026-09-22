package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 技能稳定身份及候选、发布指针，具体正文由不可变的 {@link SkillVersion} 保存。
 *
 * @param id 技能稳定标识
 * @param tenantId 技能所属租户
 * @param name 租户内唯一的技能名称
 * @param candidateVersionId 当前唯一候选版本；没有候选时为空
 * @param publishedVersionId 当前正式发布版本；从未发布时为空
 * @param enabled 是否允许新运行绑定并读取
 * @param accessEpoch 访问纪元，停用时递增以永久撤销旧运行快照
 * @param dependencyMappingRevision 依赖映射集合的权威修订号
 * @param createdBy 创建主体标识
 * @param updatedBy 最近更新主体标识
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record SkillDefinition(
        UUID id,
        UUID tenantId,
        String name,
        UUID candidateVersionId,
        UUID publishedVersionId,
        boolean enabled,
        long accessEpoch,
        long dependencyMappingRevision,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
    /** 校验技能稳定身份和两个独立治理修订，版本指针允许在首次导入前同时为空。 */
    public SkillDefinition {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        name = requireText(name, "name 不能为空");
        if (accessEpoch < 0) {
            throw new IllegalArgumentException("技能访问纪元不能为负数");
        }
        if (dependencyMappingRevision < 0) {
            throw new IllegalArgumentException("技能依赖映射修订不能为负数");
        }
        createdBy = requireText(createdBy, "createdBy 不能为空");
        updatedBy = requireText(updatedBy, "updatedBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    /** 兼容发布控制引入前的构造方式；旧当前版本同时映射为候选和已发布版本。 */
    public SkillDefinition(
            UUID id, UUID tenantId, String name, UUID currentVersionId, boolean enabled,
            long accessEpoch, String createdBy, String updatedBy, Instant createdAt, Instant updatedAt
    ) {
        this(id, tenantId, name, currentVersionId, currentVersionId, enabled, accessEpoch, 0,
                createdBy, updatedBy, createdAt, updatedAt);
    }

    /**
     * 兼容旧读取语义；新发布流程必须显式读取候选与发布指针。
     *
     * @return 候选存在时返回候选，否则返回已发布版本
     */
    public UUID currentVersionId() {
        return candidateVersionId == null ? publishedVersionId : candidateVersionId;
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
