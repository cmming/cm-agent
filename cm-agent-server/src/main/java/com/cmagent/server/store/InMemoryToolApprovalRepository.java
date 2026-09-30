package com.cmagent.server.store;

import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.core.domain.ToolApprovalScope;
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
    public List<ToolApprovalRequest> findExpiredPending(com.cmagent.core.domain.ApprovalExpiryPage page) {
        return approvals.values().stream()
                .filter(request -> request.status() == ToolApprovalStatus.PENDING && request.isExpired(page.cutoff()))
                .filter(request -> page.afterId() == null || request.expiresAt().isAfter(page.afterExpiresAt())
                        || (request.expiresAt().equals(page.afterExpiresAt())
                        && request.id().toString().compareTo(page.afterId().toString()) > 0))
                .sorted(Comparator.comparing(ToolApprovalRequest::expiresAt)
                        .thenComparing(request -> request.id().toString()))
                .limit(page.limit()).toList();
    }

    @Override
    public boolean expirePending(ToolApprovalRequest snapshot, String actor, Instant cutoff) {
        boolean[] changed = {false};
        // 单审批行与决定互斥；memory 不具备跨仓储事务回滚能力。
        approvals.computeIfPresent(snapshot.id(), (id, current) -> {
            if (current.status() != ToolApprovalStatus.PENDING || current.version() != snapshot.version()
                    || !current.tenantId().equals(snapshot.tenantId()) || !current.agentId().equals(snapshot.agentId())
                    || current.scope() != snapshot.scope() || !current.runId().equals(snapshot.runId())
                    || !java.util.Objects.equals(current.conversationId(), snapshot.conversationId())
                    || !current.expiresAt().equals(snapshot.expiresAt()) || !current.isExpired(cutoff)) {
                return current;
            }
            changed[0] = true;
            return new ToolApprovalRequest(current.id(), current.tenantId(), current.agentId(), current.scope(),
                    current.conversationId(), current.runId(), current.requestedBy(), current.requestedByDisplayName(),
                    ToolApprovalStatus.EXPIRED, current.expiresAt(), current.version() + 1, current.checkpointRef(),
                    current.createdAt(), cutoff, actor, actor, cutoff, current.items());
        });
        return changed[0];
    }

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
    public Optional<ToolApprovalRequest> findByRun(UUID tenantId, UUID agentId, UUID runId, UUID approvalId) {
        ToolApprovalRequest request = approvals.get(approvalId);
        return request != null && tenantId.equals(request.tenantId()) && agentId.equals(request.agentId())
                && runId.equals(request.runId()) && request.scope() == ToolApprovalScope.RUN
                ? Optional.of(request) : Optional.empty();
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
    public List<ToolApprovalRequest> listPendingByRun(
            UUID tenantId, UUID agentId, UUID runId, Instant now, int limit) {
        return approvals.values().stream()
                .filter(request -> tenantId.equals(request.tenantId()) && agentId.equals(request.agentId())
                        && runId.equals(request.runId()) && request.scope() == ToolApprovalScope.RUN
                        && request.status() == ToolApprovalStatus.PENDING && !request.isExpired(now))
                .sorted(Comparator.comparing(ToolApprovalRequest::createdAt).thenComparing(ToolApprovalRequest::id))
                .limit(limit).toList();
    }

    @Override
    public boolean hasPendingByRun(UUID tenantId, UUID agentId, UUID runId, Instant now) {
        return !listPendingByRun(tenantId, agentId, runId, now, 1).isEmpty();
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
                    || current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion
                    || current.isExpired(decidedAt)) {
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
    public boolean decideByRun(
            UUID tenantId, UUID agentId, UUID runId, UUID approvalId, long expectedVersion,
            ToolApprovalStatus status, Map<UUID, ToolApprovalDecision> decisions,
            String decidedBy, String decidedByDisplayName, Instant decidedAt) {
        final boolean[] changed = {false};
        approvals.computeIfPresent(approvalId, (id, current) -> {
            if (!tenantId.equals(current.tenantId()) || !agentId.equals(current.agentId())
                    || !runId.equals(current.runId()) || current.scope() != ToolApprovalScope.RUN
                    || current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion
                    || current.items().size() != decisions.size()
                    || current.isExpired(decidedAt)
                    || current.items().stream().anyMatch(item -> !decisions.containsKey(item.id()))) {
                return current;
            }
            changed[0] = true;
            return new ToolApprovalRequest(
                    current.id(), current.tenantId(), current.agentId(), current.scope(), null, current.runId(),
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

    @Override
    public boolean expireByRun(
            UUID tenantId, UUID agentId, UUID runId, UUID approvalId, long expectedVersion,
            String decidedBy, Instant decidedAt) {
        final boolean[] changed = {false};
        approvals.computeIfPresent(approvalId, (id, current) -> {
            if (!tenantId.equals(current.tenantId()) || !agentId.equals(current.agentId())
                    || !runId.equals(current.runId()) || current.scope() != ToolApprovalScope.RUN
                    || current.status() != ToolApprovalStatus.PENDING || current.version() != expectedVersion) {
                return current;
            }
            changed[0] = true;
            return new ToolApprovalRequest(
                    current.id(), current.tenantId(), current.agentId(), current.scope(), null, current.runId(),
                    current.requestedBy(), current.requestedByDisplayName(), ToolApprovalStatus.EXPIRED,
                    current.expiresAt(), current.version() + 1, current.checkpointRef(), current.createdAt(), decidedAt,
                    decidedBy, decidedBy, decidedAt, current.items());
        });
        return changed[0];
    }
}
