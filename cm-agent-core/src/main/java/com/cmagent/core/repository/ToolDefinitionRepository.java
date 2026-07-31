package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ToolDefinitionRepository 的核心领域类型。
 */
public interface ToolDefinitionRepository {
    /**
     * 定义 save 操作。
     */
    ToolDefinition save(ToolDefinition tool);

    /**
     * 更新指定租户中的工具定义。
     */
    ToolDefinition update(ToolDefinition tool);

    /**
     * 定义 findByTenantAndId 操作。
     */
    Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId);

    /**
     * 在当前事务中读取并锁定指定租户的工具定义。
     *
     * <p>不支持数据库行锁的实现沿用普通租户范围读取；JDBC 实现应覆盖此方法并持有行锁直至事务结束。</p>
     */
    default Optional<ToolDefinition> findByTenantAndIdForUpdate(UUID tenantId, UUID toolId) {
        return findByTenantAndId(tenantId, toolId);
    }

    /**
     * 定义 listByTenant 操作。
     */
    List<ToolDefinition> listByTenant(UUID tenantId);

    /**
     * 定义 delete 操作。
     */
    void delete(UUID tenantId, UUID toolId);
}
