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

    /**
     * 仅供服务端系统任务跨租户发现过期 PENDING，覆盖会话及独立 TEST。
     * 不授予执行工具权限；更新必须使用返回资源的可信归属，禁止通过 Controller 暴露。
     * 按到期时间和 UUID 规范字符串升序排列，严格在游标之后且不超过上限。
     */
    List<ToolApprovalRequest> findExpiredPending(com.cmagent.core.domain.ApprovalExpiryPage page);

    /**
     * 校验仓储快照完整归属、版本、PENDING 及截止时间后原子过期。
     * false 表示竞争失败或未到期，调用方不得收口 Run、删除检查点或写成功审计。
     * 快照只能来自仓储，不接受客户端构造的系统上下文。
     */
    boolean expirePending(ToolApprovalRequest snapshot, String actor, Instant cutoff);

    /** 保存新审批请求及其全部明细。 */
    ToolApprovalRequest save(ToolApprovalRequest request);

    /** 在完整资源边界内查询审批，跨租户、Agent 或会话均返回空。 */
    Optional<ToolApprovalRequest> find(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId);

    /** 在可信租户、Agent 和 Run 边界内查询独立试运行审批。 */
    default Optional<ToolApprovalRequest> findByRun(
            UUID tenantId, UUID agentId, UUID runId, UUID approvalId) {
        throw new UnsupportedOperationException("当前实现尚未支持运行级审批查询");
    }

    /** 按创建时间正序列出当前会话未过期的 PENDING 请求。 */
    List<ToolApprovalRequest> listPending(
            UUID tenantId, UUID agentId, UUID conversationId, Instant now, int limit);

    /** 按创建时间正序列出指定 TEST Run 未过期的 PENDING 请求。 */
    default List<ToolApprovalRequest> listPendingByRun(
            UUID tenantId, UUID agentId, UUID runId, Instant now, int limit) {
        throw new UnsupportedOperationException("当前实现尚未支持运行级待审批查询");
    }

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

    /** 判断指定 TEST Run 是否存在未过期 PENDING 请求。 */
    default boolean hasPendingByRun(UUID tenantId, UUID agentId, UUID runId, Instant now) {
        throw new UnsupportedOperationException("当前实现尚未支持运行级待审批判断");
    }

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

    /** 在可信 Run 边界内条件写入试运行审批决定。 */
    default boolean decideByRun(
            UUID tenantId,
            UUID agentId,
            UUID runId,
            UUID approvalId,
            long expectedVersion,
            ToolApprovalStatus status,
            Map<UUID, ToolApprovalDecision> decisions,
            String decidedBy,
            String decidedByDisplayName,
            Instant decidedAt
    ) {
        throw new UnsupportedOperationException("当前实现尚未支持运行级审批决定");
    }

    /** 将已过期的 PENDING 请求原子标记为 EXPIRED。 */
    boolean expire(
            UUID tenantId,
            UUID agentId,
            UUID conversationId,
            UUID approvalId,
            long expectedVersion,
            String decidedBy,
            Instant decidedAt);

    /** 在可信 Run 边界内条件标记已过期的试运行审批。 */
    default boolean expireByRun(
            UUID tenantId,
            UUID agentId,
            UUID runId,
            UUID approvalId,
            long expectedVersion,
            String decidedBy,
            Instant decidedAt
    ) {
        throw new UnsupportedOperationException("当前实现尚未支持运行级审批过期");
    }
}
