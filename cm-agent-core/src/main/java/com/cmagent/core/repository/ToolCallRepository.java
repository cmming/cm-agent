package com.cmagent.core.repository;

import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;

import java.util.List;
import java.util.UUID;

/**
 * 定义运行过程中的工具调用记录按租户持久化和查询的契约。
 */
public interface ToolCallRepository {
    /**
     * Persists a validated batch only after {@link RunToolCallBatch#requireTenant(UUID)} succeeds and before
     * any call is written. Implementations must perform this validation before their first write statement.
      *
      * @param tenantId 当前租户标识
      * @param toolCalls 本次运行产生的工具调用记录
     */
    void saveAll(UUID tenantId, RunToolCallBatch toolCalls);

    /**
     * 按租户和运行标识列出工具调用记录。
      *
      * @param tenantId 当前租户标识
      * @param runId 目标运行标识
     */
    List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId);
}
