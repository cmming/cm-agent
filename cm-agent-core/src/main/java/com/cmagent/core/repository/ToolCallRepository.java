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
     * 保存一批已通过租户校验的工具调用记录。
     *
     * <p>实现必须在第一条写入语句前执行 {@link RunToolCallBatch#requireTenant(UUID)} 核验，
     * 保证任何一条记录写入前就完成租户范围确认；失败时整个批次都不得落库，
     * 避免形成半批跨租户数据。</p>
     *
     * @param tenantId 当前租户标识
     * @param toolCalls 本次运行产生的工具调用记录批次
     */
    void saveAll(UUID tenantId, RunToolCallBatch toolCalls);

    /**
     * 按租户和运行标识列出工具调用记录。
     *
     * @param tenantId 当前租户标识
     * @param runId 目标运行标识
     * @return 仅当前租户内该运行的调用记录
     */
    List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId);
}
