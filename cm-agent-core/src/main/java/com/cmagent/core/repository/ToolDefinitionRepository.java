package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义工具元数据按租户保存、查询和删除的持久化契约。
 */
public interface ToolDefinitionRepository {
    /**
     * 在当前租户边界内保存领域记录。
     *
     * @param tool 当前工具定义
     * @return 已保存的工具定义
     */
    ToolDefinition save(ToolDefinition tool);

    /**
     * 尝试原位恢复受管的固定 LOCAL 工具定义。
     *
     * <p>该能力只供经过固定目录校验的内置示例安装流程使用。实现只能恢复租户、ID、原名称和
     * LOCAL 类型均与墓碑匹配的记录；普通创建必须继续使用 {@link #save(ToolDefinition)}，不得借此
     * 复活任意已删除工具。</p>
     *
     * @param tool 待恢复的工具定义快照
     * @return 匹配墓碑并完成恢复时返回 {@code true}，没有匹配墓碑时返回 {@code false}
     */
    default boolean restoreManagedLocalTool(ToolDefinition tool) {
        return false;
    }

    /**
     * 尝试恢复当前命令刚软删除的工具快照，仅用于无事务存储的失败补偿。
     *
     * <p>实现必须要求租户、ID、墓碑原名称和类型与快照完全匹配。业务创建和重新安装流程不得调用该方法。</p>
     *
     * @param tool 待补偿恢复的工具定义快照
     * @return 成功恢复时返回 {@code true}，没有可匹配的刚删除记录时返回 {@code false}
     */
    default boolean restoreDeletedToolForCompensation(ToolDefinition tool) {
        return false;
    }

    /**
     * 更新指定租户中的工具定义。
     *
     * @param tool 包含最新字段的工具定义
     * @return 已更新的工具定义
     */
    ToolDefinition update(ToolDefinition tool);

    /**
     * 按租户和资源标识查询唯一记录。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @return 命中时返回工具定义；跨租户或不存在一律返回空
     */
    Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId);

    /**
     * 在当前事务中读取并锁定指定租户的工具定义。
     *
     * <p>不支持数据库行锁的实现沿用普通租户范围读取；JDBC 实现应覆盖此方法并持有行锁直至事务结束。</p>
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @return 加锁后的工具定义；不存在时返回空
     */
    default Optional<ToolDefinition> findByTenantAndIdForUpdate(UUID tenantId, UUID toolId) {
        return findByTenantAndId(tenantId, toolId);
    }

    /**
     * 按租户边界列出可见记录。
     *
     * @param tenantId 当前租户标识
     * @return 仅当前租户的工具定义列表
     */
    List<ToolDefinition> listByTenant(UUID tenantId);

    /**
     * 判断工具是否已经产生需要长期保留的调用历史。
     *
     * <p>调用历史存在时不得物理删除工具定义，否则会破坏运行历史的可追溯性。</p>
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @return 已有调用历史时返回 {@code true}
     */
    boolean hasToolCallHistory(UUID tenantId, UUID toolId);

    /**
     * 从管理面删除工具，但实现必须保留运行中调用稍后写入历史所需的引用锚点。
     *
     * <p>已产生调用历史的工具不能通过本方法物理删除；删除后写入的 ToolCall 靠工具墓碑
     * 通过外键校验并保留审计链路，墓碑不占用原工具名称。</p>
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     */
    void delete(UUID tenantId, UUID toolId);
}
