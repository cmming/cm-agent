package com.cmagent.core.audit;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository {

    void append(AuditEvent event);

    List<AuditEvent> listByTenant(UUID tenantId, int limit);
}
