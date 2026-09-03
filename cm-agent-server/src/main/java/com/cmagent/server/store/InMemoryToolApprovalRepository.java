package com.cmagent.server.store;

import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.core.repository.ToolApprovalRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅供 memory profile 和测试使用的原子审批仓储。 */
public final class InMemoryToolApprovalRepository implements ToolApprovalRepository {
    private final Map<UUID, ToolApprovalRequest> approvals = new ConcurrentHashMap<>();

    @Override
    public ToolApprovalRequest save(ToolApprovalRequest request) {
        if (approvals.putIfAbsent(request.id(), request) != null) {
            throw new IllegalStateException("审批请求已存在");
        }
        return request;
    }

    @Override
    public Optional<ToolApprovalRequest> find(UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId) {
        ToolApprovalRequest request = approvals.get(approvalId);
        return request != null && tenantId.equals(request.tenantId()) && agentId.equals(request.agentId())
                && conversationId.equals(request.conversationId()) ? Optional.of(request) : Optional.empty();
    }

    @Override
    public List<ToolApprovalRequest> listPending(
            UUID tenantId, UUID agentId, UUID conversationId, Instant now, int limit) {
        return approvals.values().stream()
                .filter(request -> tenantId.equals(request.tenantId()) && agentId.equals(request.agentId())
                        && conversationId.equals(request.conversationId())
                        && request.status() == ToolApprovalStatus.PENDING && !request.isExpired(now))
                .sorted(Comparator.comparing(ToolApprovalRequest::createdAt).thenComparing(ToolApprovalRequest::id))
                .limit(limit).toList();
    }

    @Override
    public boolean hasPending(UUID tenantId, UUID agentId, UUID conversationId, Instant now) {
        return !listPending(tenantId, agentId, conversationId, now, 1).isEmpty();
    }

    @Override
    public List<ToolApprovalRequest> listHistory(
            UUID tenantId, UUID agentId, UUID conversationId, ToolApprovalHistoryPageRequest page) {
        // UUID.compareTo 按有符号整数比较，与数据库字符列不同；用规范字符串保证分页一致。
        return approvals.values().stream()
                .filter(request -> tenantId.equals(request.tenantId()) && agentId.equals(request.agentId())
                        && conversationId.equals(request.conversationId()) && request.status().isTerminal())
                .filter(request -> page.beforeId() == null || request.decidedAt().isBefore(page.beforeDecidedAt())
                        || (request.decidedAt().equals(page.beforeDecidedAt())
                        && request.id().toString().compareTo(page.beforeId().toString()) < 0))
                .sorted(Comparator.comparing(ToolApprovalRequest::decidedAt)
                        .thenComparing(request -> request.id().toString()).reversed())
                .limit(page.limit()).toList();
    }

    @Override
    public boolean decide(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId, long expectedVersion,
            ToolApprovalStatus status, Map<UUID, ToolApprovalDecision> decisions,
            String decidedBy, String decidedByDisplayName, Instant decidedAt) {
        final boolean[] changed = {false};
        approvals.computeIfPresent(approvalId, (id, current) -> {
            if (!tenantId.equals(current.tenantId()) || !agentId.equals(current.agentId())
                    || !conversationId.equals(current.conversationId())
                    || current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion) {
                return current;
            }
            if (current.items().size() != decisions.size()
                    || current.items().stream().anyMatch(item -> !decisions.containsKey(item.id()))) {
                return current;
            }
            changed[0] = true;
            return new ToolApprovalRequest(
                    current.id(), current.tenantId(), current.agentId(), current.conversationId(), current.runId(),
                    current.requestedBy(), current.requestedByDisplayName(), status, current.expiresAt(),
                    current.version() + 1, current.checkpointRef(), current.createdAt(), decidedAt,
                    decidedBy, decidedByDisplayName, decidedAt,
                    current.items().stream().map(item -> item.decide(decisions.get(item.id()))).toList());
        });
        return changed[0];
    }

    @Override
    public boolean expire(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId, long expectedVersion,
            String decidedBy, Instant decidedAt) {
        final boolean[] changed = {false};
        approvals.computeIfPresent(approvalId, (id, current) -> {
            if (!tenantId.equals(current.tenantId()) || !agentId.equals(current.agentId())
                    || !conversationId.equals(current.conversationId())
                    || current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion) {
                return current;
            }
            changed[0] = true;
            return new ToolApprovalRequest(
                    current.id(), current.tenantId(), current.agentId(), current.conversationId(), current.runId(),
                    current.requestedBy(), current.requestedByDisplayName(), ToolApprovalStatus.EXPIRED,
                    current.expiresAt(), current.version() + 1, current.checkpointRef(), current.createdAt(), decidedAt,
                    decidedBy, decidedBy, decidedAt, current.items());
        });
        return changed[0];
    }
}
