package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * 定义 Agent 与工具授权关系的保存和查询契约。
 */
public interface ToolGrantRepository {
    /**
     * 在当前租户边界内保存领域记录。
      *
      * @param grant 工具授权定义
     */
    ToolGrant save(ToolGrant grant);

    /**
     * 按租户边界列出可见记录。
      *
      * @param tenantId 当前租户标识
     */
    List<ToolGrant> listByTenant(UUID tenantId);

    /**
     * 按租户和 Agent 标识列出授权记录。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
     */
    List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId);

    /**
     * 按租户、Agent 和工具标识精确查询授权记录。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param toolId 目标工具标识
     */
    List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId);

    /**
     * 删除指定租户中 Agent 对工具的授权。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param toolId 目标工具标识
     */
    void delete(UUID tenantId, UUID agentId, UUID toolId);

    /**
     * 删除指定 Agent 的全部工具授权。
     *
     * <p>默认实现逐条删除，以兼容尚未提供批量 SQL 的测试或扩展实现；
     * 生产实现可按需覆盖为单条批量删除语句。</p>
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     */
    default void deleteByTenantAndAgentId(UUID tenantId, UUID agentId) {
        listByTenantAndAgent(tenantId, agentId)
                .forEach(grant -> delete(tenantId, agentId, grant.toolId()));
    }

    /**
     * 删除指定租户中工具的全部授权。
      *
      * @param tenantId 当前租户标识
      * @param toolId 目标工具标识
     */
    void deleteByTenantAndToolId(UUID tenantId, UUID toolId);
}
