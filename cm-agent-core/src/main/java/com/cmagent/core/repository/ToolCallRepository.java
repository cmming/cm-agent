package com.cmagent.core.repository;

import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;

import java.util.List;
import java.util.UUID;

/**
 * ToolCallRepository 的核心领域类型。
 */
public interface ToolCallRepository {
    /**
     * Persists a validated batch only after {@link RunToolCallBatch#requireTenant(UUID)} succeeds and before
     * any call is written. Implementations must perform this validation before their first write statement.
     */
    void saveAll(UUID tenantId, RunToolCallBatch toolCalls);

    /**
     * 定义 listByTenantAndRun 操作。
     */
    List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId);
}
