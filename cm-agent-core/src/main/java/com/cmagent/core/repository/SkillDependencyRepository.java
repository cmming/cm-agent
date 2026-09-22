package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillDependency;

import java.util.List;
import java.util.UUID;

/** 不可变技能版本依赖声明的持久化扩展点。 */
public interface SkillDependencyRepository {
    /** 原子保存同一版本的全部依赖，重复逻辑键或归属不一致时整体失败。 */
    void insertAll(List<SkillDependency> dependencies);

    /** 按可信租户、技能和版本列出依赖，结果按声明位置升序返回。 */
    List<SkillDependency> list(UUID tenantId, UUID skillId, UUID versionId);
}
