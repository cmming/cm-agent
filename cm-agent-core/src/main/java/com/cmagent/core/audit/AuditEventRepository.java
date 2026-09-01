package com.cmagent.core.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 定义按租户写入和分页读取审计事件的持久化契约。
 */
public interface AuditEventRepository {

    /**
     * 向当前审计存储追加一条审计事件。
     *
     * <p>实现必须保证写入成功后才视为审计已发生；写入失败应向上抛出，
     * 由调用方的严格审计语义决定当前业务请求的失败。</p>
     *
     * @param event 待追加的审计事件
     */
    void append(AuditEvent event);

    /**
     * 批量追加审计事件。支持事务的实现应将整个批次作为一个原子单元写入。
     *
     * <p>默认实现按顺序逐条追加。批次中任何一条写入失败时，已写入部分不应被静默回滚掩盖：
     * 事务型实现应整体失败，调用方据此识别审计基础设施故障。</p>
     *
     * @param events 待追加的审计事件集合
     */
    default void appendAll(List<AuditEvent> events) {
        Objects.requireNonNull(events, "events 不能为空");
        events.forEach(this::append);
    }

    /**
     * 按租户边界列出可见记录。
     *
     * @param tenantId 当前租户标识
     * @param limit 单页最大返回数量
     * @return 仅包含当前租户的审计事件
     */
    List<AuditEvent> listByTenant(UUID tenantId, int limit);

    /**
     * 表示当前仓储实现是否支持审计事件游标分页。
     *
     * @return 支持游标分页时返回 {@code true}
     */
    default boolean supportsCursorPagination() {
        return false;
    }

    /**
     * 按游标分页读取审计事件，排序语义为 {@code createdAt DESC, id DESC}。
     *
     * <p>存量实现保持首页兼容：无游标时等价于 {@link #listByTenant(UUID, int)}；
     * 生产仓储通过覆盖此方法支持游标。实现不支持游标且收到带游标请求时抛
     * {@link UnsupportedOperationException}，不得静默回退为首页读取。</p>
     *
     * @param tenantId 当前租户标识
     * @param pageRequest 游标位置和页面容量
     * @return 位于游标之前的审计事件页
     */
    default List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        if (pageRequest.beforeCreatedAt() != null) {
            throw new UnsupportedOperationException("当前审计仓储不支持游标分页");
        }
        return listByTenant(tenantId, pageRequest.limit());
    }
}
