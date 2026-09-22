package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillTrial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 指定技能版本试运行状态的持久化扩展点。 */
public interface SkillTrialRepository {
    /** 插入与 TEST Run 同标识的新试运行。 */
    SkillTrial insert(SkillTrial trial);

    /** 按可信租户和 Run 标识查询试运行。 */
    Optional<SkillTrial> find(UUID tenantId, UUID runId);

    /** 按创建时间倒序列出指定技能版本的试运行历史。 */
    List<SkillTrial> list(UUID tenantId, UUID skillId, UUID versionId);

    /** 使用当前状态做条件更新，状态已变化时返回 {@code false}。 */
    boolean update(SkillTrial next, com.cmagent.core.domain.SkillTrialStatus expectedStatus);

    /** 在当前工作单元中锁定试运行；不存在时由实现抛出受控异常。 */
    SkillTrial lock(UUID tenantId, UUID runId);
}
