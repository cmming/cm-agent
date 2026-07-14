package com.cmagent.core.audit;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository {

    void append(AuditEvent event);

    List<AuditEvent> listByTenant(UUID tenantId, int limit);

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
