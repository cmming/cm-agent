package com.cmagent.persistence;

import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalItem;
import com.cmagent.core.domain.ToolApprovalPolicy;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.repository.ToolApprovalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 使用短事务保存审批请求、明细及条件决定，所有 SQL 都包含 tenant 边界。 */
public final class JdbcToolApprovalRepository implements ToolApprovalRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcToolApprovalRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = java.util.Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = java.util.Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public ToolApprovalRequest save(ToolApprovalRequest request) {
        return java.util.Objects.requireNonNull(transactionTemplate.execute(status -> {
            jdbcClient.sql("""
                            INSERT INTO tool_approval_requests (
                                id, tenant_id, agent_id, conversation_id, run_id, requested_by,
                                requested_by_display_name, status, expires_at, version_no, checkpoint_ref,
                                decided_by, decided_by_display_name, decided_at, created_at, updated_at)
                            VALUES (:id, :tenantId, :agentId, :conversationId, :runId, :requestedBy,
                                    :requestedByDisplayName, :status, :expiresAt, :version, :checkpointRef,
                                    :decidedBy, :decidedByDisplayName, :decidedAt, :createdAt, :updatedAt)
                            """)
                    .param("id", request.id().toString()).param("tenantId", request.tenantId().toString())
                    .param("agentId", request.agentId().toString())
                    .param("conversationId", request.conversationId().toString())
                    .param("runId", request.runId().toString()).param("requestedBy", request.requestedBy())
                    .param("requestedByDisplayName", request.requestedByDisplayName())
                    .param("status", request.status().name()).param("expiresAt", Timestamp.from(request.expiresAt()))
                    .param("version", request.version()).param("checkpointRef", request.checkpointRef())
                    .param("decidedBy", request.decidedBy()).param("decidedByDisplayName", request.decidedByDisplayName())
                    .param("decidedAt", request.decidedAt() == null ? null : Timestamp.from(request.decidedAt()))
                    .param("createdAt", Timestamp.from(request.createdAt())).param("updatedAt", Timestamp.from(request.updatedAt()))
                    .update();
            for (ToolApprovalItem item : request.items()) {
                jdbcClient.sql("""
                                INSERT INTO tool_approval_items (
                                    id, tenant_id, approval_id, tool_call_id, tool_id, tool_name, risk_level,
                                    input_summary, input_hash, approval_policy, policy_version, decision,
                                    created_at, updated_at)
                                VALUES (:id, :tenantId, :approvalId, :toolCallId, :toolId, :toolName, :riskLevel,
                                        :inputSummary, :inputHash, :policy, :policyVersion, :decision,
                                        :createdAt, :updatedAt)
                                """)
                        .param("id", item.id().toString()).param("tenantId", request.tenantId().toString())
                        .param("approvalId", request.id().toString()).param("toolCallId", item.toolCallId())
                        .param("toolId", item.toolId().toString()).param("toolName", item.toolName())
                        .param("riskLevel", item.riskLevel().name()).param("inputSummary", item.inputSummary())
                        .param("inputHash", item.inputHash()).param("policy", item.policy().name())
                        .param("policyVersion", item.policyVersion())
                        .param("decision", item.decision() == null ? null : item.decision().name())
                        .param("createdAt", Timestamp.from(request.createdAt()))
                        .param("updatedAt", Timestamp.from(request.updatedAt())).update();
            }
            return request;
        }), "审批保存结果不能为空");
    }

    @Override
    public Optional<ToolApprovalRequest> find(UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, conversation_id, run_id, requested_by,
                               requested_by_display_name, status, expires_at, version_no, checkpoint_ref,
                               decided_by, decided_by_display_name, decided_at, created_at, updated_at
                        FROM tool_approval_requests
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                          AND conversation_id = :conversationId AND id = :approvalId
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString()).param("approvalId", approvalId.toString())
                .query((rs, row) -> mapRequest(rs, items(tenantId, approvalId)))
                .optional();
    }

    @Override
    public List<ToolApprovalRequest> listPending(
            UUID tenantId, UUID agentId, UUID conversationId, Instant now, int limit) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, conversation_id, run_id, requested_by,
                               requested_by_display_name, status, expires_at, version_no, checkpoint_ref,
                               decided_by, decided_by_display_name, decided_at, created_at, updated_at
                        FROM tool_approval_requests
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND conversation_id = :conversationId
                          AND status = 'PENDING' AND expires_at > :now
                        ORDER BY created_at, id LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString()).param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> {
                    UUID id = UUID.fromString(rs.getString("id"));
                    return mapRequest(rs, items(tenantId, id));
                }).list();
    }

    @Override
    public boolean hasPending(UUID tenantId, UUID agentId, UUID conversationId, Instant now) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM tool_approval_requests
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND conversation_id = :conversationId
                          AND status = 'PENDING' AND expires_at > :now
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString()).param("now", Timestamp.from(now))
                .query(Integer.class).single() > 0;
    }

    @Override
    public List<ToolApprovalRequest> listHistory(
            UUID tenantId, UUID agentId, UUID conversationId, ToolApprovalHistoryPageRequest page) {
        // 先按既有索引的 tenant/Agent/conversation 前缀限制范围，再按决定时间与 ID 定位。
        // 不用 OFFSET，避免新决定插入首屏后导致下一页重复；读取历史绝不能顺便改变审批状态。
        String cursorCondition = page.beforeId() == null ? "" : """
                AND (decided_at < :beforeTime OR (decided_at = :beforeTime AND id < :beforeId))
                """;
        var query = jdbcClient.sql("""
                SELECT id, tenant_id, agent_id, conversation_id, run_id, requested_by,
                       requested_by_display_name, status, expires_at, version_no, checkpoint_ref,
                       decided_by, decided_by_display_name, decided_at, created_at, updated_at
                FROM tool_approval_requests
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND conversation_id = :conversationId
                  AND status <> 'PENDING' AND decided_at IS NOT NULL
                """ + cursorCondition + " ORDER BY decided_at DESC, id DESC LIMIT :limit")
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString()).param("limit", page.limit());
        if (page.beforeId() != null) {
            query = query.param("beforeTime", Timestamp.from(page.beforeDecidedAt()))
                    .param("beforeId", page.beforeId().toString());
        }
        return query.query((rs, row) -> mapRequest(rs, items(tenantId, UUID.fromString(rs.getString("id"))))).list();
    }

    @Override
    public boolean decide(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId, long expectedVersion,
            ToolApprovalStatus status, Map<UUID, ToolApprovalDecision> decisions,
            String decidedBy, String decidedByDisplayName, Instant decidedAt) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            int updated = jdbcClient.sql("""
                            UPDATE tool_approval_requests
                            SET status = :status, version_no = version_no + 1, decided_by = :decidedBy,
                                decided_by_display_name = :displayName, decided_at = :decidedAt, updated_at = :decidedAt
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                              AND conversation_id = :conversationId AND id = :approvalId
                              AND status = 'PENDING' AND version_no = :expectedVersion
                            """)
                    .param("status", status.name()).param("decidedBy", decidedBy).param("displayName", decidedByDisplayName)
                    .param("decidedAt", Timestamp.from(decidedAt)).param("tenantId", tenantId.toString())
                    .param("agentId", agentId.toString()).param("conversationId", conversationId.toString())
                    .param("approvalId", approvalId.toString()).param("expectedVersion", expectedVersion).update();
            if (updated != 1) {
                return false;
            }
            int itemUpdates = 0;
            for (var entry : decisions.entrySet()) {
                itemUpdates += jdbcClient.sql("""
                                UPDATE tool_approval_items SET decision = :decision, updated_at = :decidedAt
                                WHERE tenant_id = :tenantId AND approval_id = :approvalId
                                  AND id = :itemId AND decision IS NULL
                                """)
                        .param("decision", entry.getValue().name()).param("decidedAt", Timestamp.from(decidedAt))
                        .param("tenantId", tenantId.toString()).param("approvalId", approvalId.toString())
                        .param("itemId", entry.getKey().toString()).update();
            }
            if (itemUpdates != decisions.size()) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    @Override
    public boolean expire(
            UUID tenantId, UUID agentId, UUID conversationId, UUID approvalId, long expectedVersion,
            String decidedBy, Instant decidedAt) {
        return jdbcClient.sql("""
                        UPDATE tool_approval_requests
                        SET status = 'EXPIRED', version_no = version_no + 1, decided_by = :decidedBy,
                            decided_by_display_name = :decidedBy, decided_at = :decidedAt, updated_at = :decidedAt
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                          AND conversation_id = :conversationId AND id = :approvalId
                          AND status = 'PENDING' AND version_no = :expectedVersion
                        """)
                .param("decidedBy", decidedBy).param("decidedAt", Timestamp.from(decidedAt))
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("conversationId", conversationId.toString()).param("approvalId", approvalId.toString())
                .param("expectedVersion", expectedVersion).update() == 1;
    }

    private List<ToolApprovalItem> items(UUID tenantId, UUID approvalId) {
        return jdbcClient.sql("""
                        SELECT id, approval_id, tool_call_id, tool_id, tool_name, risk_level,
                               input_summary, input_hash, approval_policy, policy_version, decision
                        FROM tool_approval_items
                        WHERE tenant_id = :tenantId AND approval_id = :approvalId ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId.toString()).param("approvalId", approvalId.toString())
                .query(this::mapItem).list();
    }

    private ToolApprovalRequest mapRequest(ResultSet rs, List<ToolApprovalItem> items) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ToolApprovalRequest(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("agent_id")), UUID.fromString(rs.getString("conversation_id")),
                UUID.fromString(rs.getString("run_id")), rs.getString("requested_by"),
                rs.getString("requested_by_display_name"), ToolApprovalStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(), rs.getLong("version_no"), rs.getString("checkpoint_ref"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("decided_by"), rs.getString("decided_by_display_name"),
                decidedAt == null ? null : decidedAt.toInstant(), items);
    }

    private ToolApprovalItem mapItem(ResultSet rs, int row) throws SQLException {
        String decision = rs.getString("decision");
        return new ToolApprovalItem(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("approval_id")),
                rs.getString("tool_call_id"), UUID.fromString(rs.getString("tool_id")), rs.getString("tool_name"),
                ToolRiskLevel.valueOf(rs.getString("risk_level")), rs.getString("input_summary"),
                rs.getString("input_hash"), ToolApprovalPolicy.valueOf(rs.getString("approval_policy")),
                rs.getLong("policy_version"), decision == null ? null : ToolApprovalDecision.valueOf(decision));
    }
}
