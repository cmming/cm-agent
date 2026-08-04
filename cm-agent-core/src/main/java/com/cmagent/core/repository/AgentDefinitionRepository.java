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
