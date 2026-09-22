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
    /**
     * 列出当前租户中引用指定技能的全部正式绑定。
     *
     * <p>依赖映射变更需要按实际绑定策略解析受影响版本和 Agent，因此必须同时拿到跟随型和
     * 固定型绑定。实现按创建时间和标识稳定排序，便于生成可复现的检查结果。</p>
     *
     * @param tenantId 当前租户标识
     * @param skillId 目标技能标识
     * @return 该技能在当前租户下的全部正式绑定
     */
    default List<AgentSkillBinding> listBySkill(UUID tenantId, UUID skillId) {
        return List.of();
    }
    /**
     * 列出当前租户中会把该技能作跟随当前发布版本的 Agent。
     *
     * <p>发布预检和映射更新必须按实际绑定策略枚举受影响 Agent；固定型绑定使用各自的
     * 固定版本，不能与跟随型混在一起检查。实现只返回 {@code FOLLOW_PUBLISHED} 绑定。</p>
     *
     * @param tenantId 当前租户标识
     * @param skillId 目标技能标识
     * @return 跟随当前发布版本的 Agent 标识，按绑定创建时间和标识稳定排序
     */
    default List<UUID> listFollowerAgentIds(UUID tenantId, UUID skillId) {
        return List.of();
    }
    /** 在当前工作单元中锁定 Agent 的绑定序列化边界。 */
    void lockAgent(UUID tenantId, UUID agentId);
    /** 使用预期修订原子更新跟随或固定策略，冲突时返回 {@code false}。 */
    default boolean updateStrategy(AgentSkillBinding next, long expectedRevision) {
        throw new UnsupportedOperationException("当前实现尚未支持技能绑定策略更新");
    }
}
