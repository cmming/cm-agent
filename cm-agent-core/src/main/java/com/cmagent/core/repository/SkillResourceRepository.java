package com.cmagent.core.repository;

import com.cmagent.core.domain.SkillResource;

import java.util.List;
import java.util.UUID;

/** 不可变技能版本文本资源的批量持久化扩展点。 */
public interface SkillResourceRepository {
    /** 原子插入同一版本的资源集合；任一重复或归属错误时整体失败。 */
    void insertAll(List<SkillResource> resources);
    /** 按租户、技能和固定版本列出资源，按路径升序返回。 */
    List<SkillResource> list(UUID tenantId, UUID skillId, UUID versionId);
}
