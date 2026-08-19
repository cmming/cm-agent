package com.cmagent.core.repository;

import com.cmagent.core.domain.AgentDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义 Agent 配置按租户保存、查询和删除的持久化契约。
 */
public interface AgentDefinitionRepository {
    /**
     * 在当前租户边界内保存领域记录。
      *
      * @param agent 当前 Agent 定义
     */
    AgentDefinition save(AgentDefinition agent);

    /**
     * 更新当前租户内既有 Agent 的可编辑定义。
     *
     * <p>实现不得通过 {@link #save(AgentDefinition)} 伪装更新，因为 JDBC 实现的
     * {@code save} 只负责插入，且更新必须保留创建人和既有工具关联。</p>
     *
     * @param agent 包含最新可编辑字段的 Agent 定义
     * @return 更新后的 Agent 定义
     */
    default AgentDefinition update(AgentDefinition agent) {
        throw new UnsupportedOperationException("当前 Agent 仓储不支持更新");
    }

    /**
     * 按租户和资源标识查询唯一记录。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
     */
    Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId);

    /**
     * 按租户边界列出可见记录。
      *
      * @param tenantId 当前租户标识
     */
    List<AgentDefinition> listByTenant(UUID tenantId);

    /**
     * 判断 Agent 是否已有不可随删除丢失的会话或运行历史。
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     * @return 存在历史依赖时为 {@code true}
     */
    default boolean hasUsageHistory(UUID tenantId, UUID agentId) {
        return false;
    }

    /**
     * 删除当前租户内的 Agent 定义。
     *
     * <p>调用方必须先解除工具授权并检查 {@link #hasUsageHistory(UUID, UUID)}；
     * 这样不会破坏运行审计链路或遗留外键。</p>
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     * @return 实际删除记录时为 {@code true}
     */
    default boolean delete(UUID tenantId, UUID agentId) {
        throw new UnsupportedOperationException("当前 Agent 仓储不支持删除");
    }

    /**
     * 在同一租户内建立 Agent 与工具的关联。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param toolId 目标工具标识
     */
    AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId);

    /**
     * 移除指定 Agent 关联的工具。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param toolId 目标工具标识
     */
    AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId);
}
