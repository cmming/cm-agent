package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillVersion;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 不可变技能版本的持久化扩展点。 */
public interface SkillVersionRepository {
    /** 插入新版本；版本标识或同技能版本号重复时必须失败。 */
    void insert(SkillVersion version);
    /** 按租户、技能和版本三重边界查询。 */
    Optional<SkillVersion> find(UUID tenantId, UUID skillId, UUID versionId);
    /** 按版本号倒序列出一个技能的全部不可变版本。 */
    default List<SkillVersion> list(UUID tenantId, UUID skillId) {
        throw new UnsupportedOperationException("当前实现尚未支持技能版本历史");
    }
}
