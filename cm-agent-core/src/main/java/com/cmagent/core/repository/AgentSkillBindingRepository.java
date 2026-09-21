package com.cmagent.core.repository;

import com.cmagent.core.domain.AgentSkillBinding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 与技能稳定身份绑定关系的持久化扩展点。 */
public interface AgentSkillBindingRepository {
    /** 列出当前租户 Agent 的绑定，按创建时间和标识稳定排序。 */
    List<AgentSkillBinding> list(UUID tenantId, UUID agentId);
    /** 查询当前租户 Agent 与技能的唯一绑定。 */
    Optional<AgentSkillBinding> find(UUID tenantId, UUID agentId, UUID skillId);
    /** 插入新绑定；同一 Agent/Skill 组合重复时必须失败。 */
    void insert(AgentSkillBinding binding);
    /** 删除唯一绑定，实际删除时返回 {@code true}。 */
    boolean delete(UUID tenantId, UUID agentId, UUID skillId);
    /** 统计当前租户中引用指定技能的 Agent 数量。 */
    long countBySkill(UUID tenantId, UUID skillId);
    /** 在当前工作单元中锁定 Agent 的绑定序列化边界。 */
    void lockAgent(UUID tenantId, UUID agentId);
}
