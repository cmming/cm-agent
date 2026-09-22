package com.cmagent.core.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次 Run 固定的技能授权快照；空列表也是必须持久化的有效快照。
 *
 * @param tenantId Run 所属租户
 * @param runId Run 标识
 * @param agentId 执行 Run 的 Agent 标识
 * @param formatVersion 快照格式版本；1 为旧绑定格式，2 包含来源和依赖解析
 * @param skills 固定的技能引用集合
 * @param createdAt 快照创建时间
 */
public record RunSkillSnapshot(
        UUID tenantId,
        UUID runId,
        UUID agentId,
        int formatVersion,
        List<SkillSnapshotRef> skills,
        Instant createdAt
) {
    /** 冻结引用集合并拒绝重复技能，format 1 保持只读恢复兼容。 */
    public RunSkillSnapshot {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (formatVersion != 1 && formatVersion != 2) {
            throw new IllegalArgumentException("不支持的技能快照格式");
        }
        skills = List.copyOf(Objects.requireNonNull(skills, "skills 不能为空"));
        Set<UUID> skillIds = new HashSet<>();
        if (skills.stream().anyMatch(reference -> !skillIds.add(reference.skillId()))) {
            throw new IllegalArgumentException("运行技能不能重复");
        }
    }
}
