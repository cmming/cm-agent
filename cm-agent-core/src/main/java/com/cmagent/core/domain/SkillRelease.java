package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次不可变、追加式的技能发布事实。
 *
 * @param id 发布记录标识
 * @param tenantId 发布所属租户
 * @param skillId 发布所属技能
 * @param releaseNo 技能内从 1 开始递增的发布序号
 * @param action 发布、回滚或迁移基线动作
 * @param previousVersionId 动作发生前的已发布版本；首次发布或基线可为空
 * @param versionId 本次动作最终生效的版本
 * @param preflightId 发布时采用的即时预检；基线为空
 * @param trialRunId 候选发布采用的合格试运行；回滚和基线为空
 * @param createdBy 执行动作的可信主体
 * @param createdAt 动作发生时间
 */
public record SkillRelease(
        UUID id,
        UUID tenantId,
        UUID skillId,
        long releaseNo,
        SkillReleaseAction action,
        UUID previousVersionId,
        UUID versionId,
        UUID preflightId,
        UUID trialRunId,
        String createdBy,
        Instant createdAt
) {
    /** 按发布动作校验依据字段，避免追加无法审计或语义冲突的发布记录。 */
    public SkillRelease {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        if (releaseNo < 1) {
            throw new IllegalArgumentException("发布序号必须从 1 开始");
        }
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        createdBy = requireText(createdBy, "createdBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (action == SkillReleaseAction.BASELINE && (preflightId != null || trialRunId != null)) {
            throw new IllegalArgumentException("基线发布不能引用预检或试运行");
        }
        if (action == SkillReleaseAction.PUBLISH) {
            Objects.requireNonNull(preflightId, "发布预检标识不能为空");
            Objects.requireNonNull(trialRunId, "发布试运行标识不能为空");
        }
        if (action == SkillReleaseAction.ROLLBACK) {
            Objects.requireNonNull(preflightId, "回滚预检标识不能为空");
            if (trialRunId != null) {
                throw new IllegalArgumentException("回滚不能引用候选试运行");
            }
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
