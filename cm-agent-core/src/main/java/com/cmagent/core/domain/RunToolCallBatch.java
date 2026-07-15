package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable tool-call batch that rejects cross-tenant records before persistence starts.
 */
public record RunToolCallBatch(UUID tenantId, List<RunToolCall> toolCalls) {
    public RunToolCallBatch {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls 不能为空"));
        if (toolCalls.stream().anyMatch(toolCall -> !tenantId.equals(toolCall.tenantId()))) {
            throw new IllegalArgumentException("toolCalls 必须全部属于 tenantId");
        }
    }

    /**
     * Verifies the explicit repository scope before an implementation performs its first write.
     */
    public void requireTenant(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        if (!this.tenantId.equals(tenantId)) {
            throw new IllegalArgumentException("tenantId 与 toolCalls 批次不匹配");
        }
    }
}
