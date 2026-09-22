package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 技能依赖预检汇总及明细的原子持久化扩展点。 */
public interface SkillPreflightRepository {
    /** 原子保存一次预检及其全部明细，并返回保存后的汇总。 */
    SkillPreflightCheck save(SkillPreflightCheck check, List<SkillPreflightItem> items);

    /** 按可信租户和预检标识查询汇总。 */
    Optional<SkillPreflightCheck> find(UUID tenantId, UUID checkId);

    /** 按可信租户列出一次预检的全部明细。 */
    List<SkillPreflightItem> listItems(UUID tenantId, UUID checkId);

    /** 按完成时间倒序列出指定技能版本的预检历史。 */
    List<SkillPreflightCheck> list(UUID tenantId, UUID skillId, UUID versionId);
}
