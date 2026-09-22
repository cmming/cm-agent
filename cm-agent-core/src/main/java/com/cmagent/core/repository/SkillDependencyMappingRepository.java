package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillDependencyMapping;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 技能逻辑依赖到租户工具映射的持久化扩展点。 */
public interface SkillDependencyMappingRepository {
    /** 查询当前租户技能的一个逻辑依赖映射。 */
    Optional<SkillDependencyMapping> find(UUID tenantId, UUID skillId, String logicalKey);

    /** 按逻辑键升序列出当前租户技能的全部映射。 */
    List<SkillDependencyMapping> list(UUID tenantId, UUID skillId);

    /** 插入或替换一个映射；调用方须在同一工作单元中递增技能映射修订。 */
    SkillDependencyMapping save(SkillDependencyMapping mapping);

    /** 删除一个映射，实际删除时返回 {@code true}。 */
    boolean delete(UUID tenantId, UUID skillId, String logicalKey);

    /** 锁定技能映射集合的序列化边界，避免修订号与行集合分离。 */
    void lockSkill(UUID tenantId, UUID skillId);
}
