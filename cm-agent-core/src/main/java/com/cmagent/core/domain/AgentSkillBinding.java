package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 与技能稳定身份之间的一次绑定；解绑后重绑必须创建新的绑定标识。
 *
 * @param id 绑定标识，用于永久区分解绑前后的授权关系
 * @param tenantId 绑定所属租户
 * @param agentId 被绑定的 Agent 标识
 * @param skillId 被绑定的技能标识
 * @param boundBy 执行绑定的主体标识
 * @param createdAt 绑定创建时间
 */
public record AgentSkillBinding(
        UUID id,
        UUID tenantId,
        UUID agentId,
        UUID skillId,
        String boundBy,
        Instant createdAt
) {

    /** 校验绑定的稳定身份和可信审计主体。 */
    public AgentSkillBinding {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(boundBy, "boundBy 不能为空");
        if (boundBy.isBlank()) {
            throw new IllegalArgumentException("boundBy 不能为空");
        }
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}
