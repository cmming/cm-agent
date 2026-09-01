package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次运行内工具调用记录的不可变批次；落库前拒绝跨租户记录混入。
 *
 * <p>批次构造时即校验每条记录归属 {@code tenantId}，批量写入的租户条件在进入存储层前
 * 就已闭环，避免实现遗漏 {@code WHERE tenant_id} 时产生跨租户写入。</p>
 *
 * @param tenantId 批次统一归属租户
 * @param toolCalls 本次运行产生的工具调用记录，全部同租户且不可变
 */
public record RunToolCallBatch(UUID tenantId, List<RunToolCall> toolCalls) {
    /**
     * 校验批次归属并复制工具调用集合，防止外部修改。
     *
     * @param tenantId 当前租户标识
     * @param toolCalls 本次运行产生的工具调用记录
     */
    public RunToolCallBatch {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls 不能为空"));
        if (toolCalls.stream().anyMatch(toolCall -> !tenantId.equals(toolCall.tenantId()))) {
            throw new IllegalArgumentException("toolCalls 必须全部属于 tenantId");
        }
    }

    /**
     * 在第一次写入前核验仓储方法的显式租户范围与批次归属一致。
     *
     * <p>实现必须在第一条写入语句前调用该方法；不匹配立即失败，
     * 防止半批写入形成跨租户数据。</p>
     *
     * @param tenantId 当前租户标识
     * @throws IllegalArgumentException 与批次租户不一致时抛出
     */
    public void requireTenant(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        if (!this.tenantId.equals(tenantId)) {
            throw new IllegalArgumentException("tenantId 与 toolCalls 批次不匹配");
        }
    }
}
