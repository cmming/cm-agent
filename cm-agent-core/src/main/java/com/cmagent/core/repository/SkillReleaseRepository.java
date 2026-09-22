package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillRelease;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 追加式技能发布事实的持久化扩展点。 */
public interface SkillReleaseRepository {
    /** 插入发布事实；同技能发布序号冲突时必须失败。 */
    SkillRelease insert(SkillRelease release);

    /** 按可信租户和发布标识查询。 */
    Optional<SkillRelease> find(UUID tenantId, UUID releaseId);

    /** 按发布序号倒序列出一个技能的发布历史。 */
    List<SkillRelease> list(UUID tenantId, UUID skillId);

    /** 在当前工作单元中锁定并返回最近发布事实；尚未发布时返回空。 */
    Optional<SkillRelease> lockLatest(UUID tenantId, UUID skillId);
}
