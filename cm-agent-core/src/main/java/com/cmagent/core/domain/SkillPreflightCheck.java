package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次技能依赖预检的不可变汇总。
 *
 * @param id 预检标识
 * @param tenantId 预检所属租户
 * @param skillId 目标技能
 * @param versionId 目标不可变版本
 * @param mappingRevision 预检采用的技能级映射修订
 * @param scope 发起预检的治理入口
 * @param agentId 单 Agent 预检的目标；结构或发布范围可为空
 * @param status 预检汇总状态
 * @param createdBy 发起预检的可信主体
 * @param createdAt 预检完成时间
 */
public record SkillPreflightCheck(
        UUID id,
        UUID tenantId,
        UUID skillId,
        UUID versionId,
        long mappingRevision,
        SkillPreflightScope scope,
        UUID agentId,
        SkillPreflightStatus status,
        String createdBy,
        Instant createdAt
) {
    /** 校验预检身份和权威映射修订。 */
    public SkillPreflightCheck {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        if (mappingRevision < 0) {
            throw new IllegalArgumentException("映射修订不能为负数");
        }
        Objects.requireNonNull(scope, "scope 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if ((scope == SkillPreflightScope.AGENT || scope == SkillPreflightScope.BINDING) && agentId == null) {
            throw new NullPointerException("Agent 预检必须指定 agentId");
        }
        createdBy = requireText(createdBy, "createdBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
