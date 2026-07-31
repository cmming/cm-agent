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
     * 尝试原位恢复受管的固定 LOCAL 工具定义。
     *
     * <p>该能力只供经过固定目录校验的内置示例安装流程使用。实现只能恢复租户、ID、原名称和
     * LOCAL 类型均与墓碑匹配的记录；普通创建必须继续使用 {@link #save(ToolDefinition)}，不得借此
     * 复活任意已删除工具。</p>
     *
     * @return 匹配墓碑并完成恢复时返回 {@code true}，没有匹配墓碑时返回 {@code false}
     */
    default boolean restoreManagedLocalTool(ToolDefinition tool) {
        return false;
    }

    /**
     * 尝试恢复当前命令刚软删除的工具快照，仅用于无事务存储的失败补偿。
     *
     * <p>实现必须要求租户、ID、墓碑原名称和类型与快照完全匹配。业务创建和重新安装流程不得
     * 调用该方法。</p>
     */
    default boolean restoreDeletedToolForCompensation(ToolDefinition tool) {
        return false;
    }

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
     * 判断工具是否已经产生需要长期保留的调用历史。
     *
     * <p>调用历史存在时不得物理删除工具定义，否则会破坏运行历史的可追溯性。</p>
     */
    boolean hasToolCallHistory(UUID tenantId, UUID toolId);

    /**
     * 从管理面删除工具，但实现必须保留运行中调用稍后写入历史所需的引用锚点。
     */
    void delete(UUID tenantId, UUID toolId);
}
