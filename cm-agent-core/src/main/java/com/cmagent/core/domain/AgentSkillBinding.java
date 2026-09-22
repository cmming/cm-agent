package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 与技能稳定身份之间的一次正式绑定及其取版策略。
 *
 * @param id 绑定标识，用于永久区分解绑前后的授权关系
 * @param tenantId 绑定所属租户
 * @param agentId 被绑定的 Agent 标识
 * @param skillId 被绑定的技能标识
 * @param mode 跟随当前发布或固定已发布历史版本
 * @param pinnedVersionId 固定模式指定的版本；跟随模式为空
 * @param revision 策略乐观锁修订，从零开始
 * @param boundBy 首次执行绑定的主体标识
 * @param updatedBy 最近修改策略的主体标识
 * @param createdAt 绑定创建时间
 * @param updatedAt 最近更新时间
 */
public record AgentSkillBinding(
        UUID id, UUID tenantId, UUID agentId, UUID skillId,
        SkillBindingMode mode, UUID pinnedVersionId, long revision,
        String boundBy, String updatedBy, Instant createdAt, Instant updatedAt
) {
    /** 校验正式绑定身份和模式字段，防止跟随与固定语义同时出现。 */
    public AgentSkillBinding {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        if (mode == SkillBindingMode.PINNED) {
            Objects.requireNonNull(pinnedVersionId, "固定版本不能为空");
        } else if (pinnedVersionId != null) {
            throw new IllegalArgumentException("跟随发布的绑定不能携带固定版本");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("绑定修订不能为负数");
        }
        boundBy = requireText(boundBy, "boundBy 不能为空");
        updatedBy = requireText(updatedBy, "updatedBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    /** 兼容既有绑定入口，默认跟随当前已发布版本。 */
    public AgentSkillBinding(UUID id, UUID tenantId, UUID agentId, UUID skillId, String boundBy, Instant createdAt) {
        this(id, tenantId, agentId, skillId, SkillBindingMode.FOLLOW_PUBLISHED, null, 0,
                boundBy, boundBy, createdAt, createdAt);
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
