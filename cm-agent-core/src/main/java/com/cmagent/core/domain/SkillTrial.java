package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 指定技能版本试运行的治理状态。
 *
 * @param runId 复用真实运行链路的 TEST Run 标识
 * @param tenantId 试运行所属租户
 * @param skillId 被临时注入的技能
 * @param versionId 被临时注入的不可变版本
 * @param agentId 执行真实模型调用的 Agent
 * @param mappingRevision 试运行固定的技能级映射修订
 * @param status 当前试运行状态
 * @param qualifiesRelease 是否满足候选发布依据要求
 * @param createdBy 发起试运行的可信主体
 * @param createdAt 创建时间
 * @param updatedAt 最近状态更新时间
 */
public record SkillTrial(
        UUID runId,
        UUID tenantId,
        UUID skillId,
        UUID versionId,
        UUID agentId,
        long mappingRevision,
        SkillTrialStatus status,
        boolean qualifiesRelease,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    /** 校验试运行资源边界，并确保只有已触发成功状态能作为发布依据。 */
    public SkillTrial {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        if (mappingRevision < 0) {
            throw new IllegalArgumentException("映射修订不能为负数");
        }
        Objects.requireNonNull(status, "status 不能为空");
        if (qualifiesRelease && status != SkillTrialStatus.PASSED) {
            throw new IllegalArgumentException("只有 PASSED 试运行可以作为发布依据");
        }
        createdBy = requireText(createdBy, "createdBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
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
