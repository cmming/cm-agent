package com.cmagent.core.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AuditEventRepository 的核心领域类型。
 */
public interface AuditEventRepository {

    /**
     * 定义 append 操作。
     */
    void append(AuditEvent event);

    /**
     * 批量追加审计事件。支持事务的实现应将整个批次作为一个原子单元写入。
     */
    default void appendAll(List<AuditEvent> events) {
        Objects.requireNonNull(events, "events 不能为空");
        events.forEach(this::append);
    }

    /**
     * 定义 listByTenant 操作。
     */
    List<AuditEvent> listByTenant(UUID tenantId, int limit);

    /**
     * 执行 supportsCursorPagination 操作。
     */
    default boolean supportsCursorPagination() {
        return false;
    }

    /**
     * Lists audit events in {@code createdAt DESC, id DESC} order using a validated keyset request.
     * Existing implementations remain source-compatible for the first page; production repositories
     * override this method to support cursors.
     */
    default List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        if (pageRequest.beforeCreatedAt() != null) {
            throw new UnsupportedOperationException("当前审计仓储不支持游标分页");
        }
        return listByTenant(tenantId, pageRequest.limit());
    }
}
