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
      * @param event 待追加的审计事件
     */
    void append(AuditEvent event);

    /**
     * 批量追加审计事件。支持事务的实现应将整个批次作为一个原子单元写入。
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
     * Lists audit events in {@code createdAt DESC, id DESC} order using a validated keyset request.
     * Existing implementations remain source-compatible for the first page; production repositories
     * override this method to support cursors.
      *
      * @param tenantId 当前租户标识
      * @param pageRequest 游标位置和页面容量
     */
    default List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        if (pageRequest.beforeCreatedAt() != null) {
            throw new UnsupportedOperationException("当前审计仓储不支持游标分页");
        }
        return listByTenant(tenantId, pageRequest.limit());
    }
}
