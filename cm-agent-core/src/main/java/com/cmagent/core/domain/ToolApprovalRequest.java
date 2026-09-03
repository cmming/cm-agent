package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 保存一次 AgentScope ASK 暂停与审批决定的租户隔离快照。
 *
 * @param id 审批请求公开标识
 * @param tenantId 可信认证上下文中的租户标识
 * @param agentId 当前 Agent 标识
 * @param conversationId 当前会话标识
 * @param runId 被暂停的 Run 标识
 * @param requestedBy 发起运行的主体标识
 * @param requestedByDisplayName 发起人展示名称快照
 * @param status 审批请求状态
 * @param expiresAt 服务端权威过期时间
 * @param version 乐观锁版本
 * @param checkpointRef 加密 AgentState 检查点引用，不对前端暴露
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @param decidedBy 审批主体标识；PENDING 时为 {@code null}
 * @param decidedByDisplayName 审批人展示名称；PENDING 时为 {@code null}
 * @param decidedAt 决定生效时间；PENDING 时为 {@code null}
 * @param items 本轮全部待确认调用
 */
public record ToolApprovalRequest(
        UUID id,
        UUID tenantId,
        UUID agentId,
        UUID conversationId,
        UUID runId,
        String requestedBy,
        String requestedByDisplayName,
        ToolApprovalStatus status,
        Instant expiresAt,
        long version,
        String checkpointRef,
        Instant createdAt,
        Instant updatedAt,
        String decidedBy,
        String decidedByDisplayName,
        Instant decidedAt,
        List<ToolApprovalItem> items
) {
    public ToolApprovalRequest {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy 不能为空");
        }
        requestedByDisplayName = requestedByDisplayName == null ? requestedBy : requestedByDisplayName;
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负数");
        }
        if (checkpointRef == null || checkpointRef.isBlank()) {
            throw new IllegalArgumentException("checkpointRef 不能为空");
        }
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        items = List.copyOf(items);
        if (items.isEmpty() || items.stream().anyMatch(item -> !id.equals(item.approvalId()))) {
            throw new IllegalArgumentException("审批明细不能为空且必须属于当前请求");
        }
        if (status == ToolApprovalStatus.PENDING && (decidedBy != null || decidedAt != null)) {
            throw new IllegalArgumentException("PENDING 请求不能包含审批人或决定时间");
        }
        if (status.isTerminal() && (decidedBy == null || decidedAt == null)) {
            throw new IllegalArgumentException("已决定请求必须包含审批人和决定时间");
        }
    }

    /** @return 是否已达到或超过服务端过期时间 */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
