package com.cmagent.core.repository;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.SkillDefinition;

import java.util.Optional;
import java.util.UUID;

/** 技能稳定身份、当前版本指针和启停状态的持久化扩展点。 */
public interface SkillDefinitionRepository {
    /** 按可信租户和技能标识查询，不存在或跨租户时返回空。 */
    Optional<SkillDefinition> find(UUID tenantId, UUID skillId);
    /** 按可信租户和稳定名称查询。 */
    Optional<SkillDefinition> findByName(UUID tenantId, String name);
    /** 按名称关键字和启用状态分页查询，结果必须稳定排序。 */
    ApiPageResponse<SkillDefinition> list(UUID tenantId, String query, Boolean enabled, ApiPageRequest page);
    /** 插入新定义；租户内 ID 或名称重复时必须失败。 */
    SkillDefinition insert(SkillDefinition definition);
    /** 在当前工作单元中锁定定义；不存在时抛出受控异常。 */
    SkillDefinition lock(UUID tenantId, UUID skillId);
    /** 使用预期当前版本做乐观更新，冲突时返回 {@code false}。 */
    boolean updateCurrent(SkillDefinition next, UUID expectedVersionId);
    /** 更新启停状态和访问纪元；目标定义必须已经存在。 */
    void updateEnabled(SkillDefinition next);
}
