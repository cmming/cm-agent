package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义租户隔离的工具审批持久化与乐观锁更新契约。
 *
 * <p>实现必须把请求和全部明细作为一个原子单元写入；决定更新只能命中 PENDING 且版本一致的请求。
 * 所有查询都必须带 tenant、Agent 和 conversation 条件，不能依赖客户端传入的 tenant。</p>
 */
public interface ToolApprovalRepository {

    /** 保存新审批请求及其全部明细。 */
    ToolApprovalRequest save(ToolApprovalRequest request);

    /** 在完整资源边界内查询审批，跨租户、Agent 或会话均返回空。 */
    Optional<ToolApprovalRequest> find(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId);

    /** 按创建时间正序列出当前会话未过期的 PENDING 请求。 */
    List<ToolApprovalRequest> listPending(
            UUID tenantId, UUID agentId, UUID conversationId, Instant now, int limit);

    /**
     * 在可信租户和会话边界内分页查询已处理审批，不返回 PENDING，也不触发过期更新或运行恢复。
     *
     * @param tenantId 从认证上下文获得的租户标识，不能接受客户端覆盖
     * @param agentId 已校验归属的 Agent 标识
     * @param conversationId 已校验归属的会话标识
     * @param page 决定时间与审批标识组成的降序游标，结果不超过指定上限
     * @return 按决定时间及 UUID 规范字符串降序排列的审批和完整明细
     */
    List<ToolApprovalRequest> listHistory(
            UUID tenantId, UUID agentId, UUID conversationId, ToolApprovalHistoryPageRequest page);

    /** 判断当前会话是否存在未过期 PENDING 请求，用于阻止并发发送新消息。 */
    boolean hasPending(UUID tenantId, UUID agentId, UUID conversationId, Instant now);

    /**
     * 使用条件更新原子写入全量明细决定和请求终态。
     *
     * @return 仅当 PENDING 状态、版本及资源边界全部匹配并成功更新时返回 {@code true}
     */
    boolean decide(
            UUID tenantId,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            ToolApprovalStatus status,
            Map<UUID, ToolApprovalDecision> decisions,
            String decidedBy,
            String decidedByDisplayName,
            Instant decidedAt);

    /** 将已过期的 PENDING 请求原子标记为 EXPIRED。 */
    boolean expire(
            UUID tenantId,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            String decidedBy,
            Instant decidedAt);
}
