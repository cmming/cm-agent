package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditPageRequest;
import com.cmagent.core.audit.AuditEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class JdbcAuditEventRepository implements AuditEventRepository {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcAuditEventRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public void append(AuditEvent event) {
        jdbcClient.sql("""
                        INSERT INTO audit_events (
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            :principalId,
                            :eventType,
                            :resourceType,
                            :resourceId,
                            :status,
                            :message,
                            :createdAt
                        )
                        """)
                .param("id", event.id().toString())
                .param("tenantId", event.tenantId().toString())
                .param("principalId", event.principalId())
                .param("eventType", event.eventType())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("status", event.status())
                .param("message", event.message())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }

    @Override
    public void appendAll(List<AuditEvent> events) {
        List<AuditEvent> batch = List.copyOf(Objects.requireNonNull(events, "events 不能为空"));
        transactionTemplate.executeWithoutResult(status -> batch.forEach(this::append));
    }

    @Override
    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        return query(tenantId, limit, null);
    }

    @Override
    public boolean supportsCursorPagination() {
        return true;
    }

    @Override
    public List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        return query(tenantId, pageRequest.limit(), pageRequest);
    }

    private List<AuditEvent> query(UUID tenantId, int limit, AuditPageRequest pageRequest) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        String sql = pageRequest == null || pageRequest.beforeCreatedAt() == null
                ? """
                        SELECT
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        FROM audit_events
                        WHERE tenant_id = :tenantId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """
                : """
                        SELECT
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        FROM audit_events
                        WHERE tenant_id = :tenantId
                          AND (created_at < :beforeCreatedAt
                               OR (created_at = :beforeCreatedAt AND id < :beforeId))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """;
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("tenantId", tenantId.toString())
                .param("limit", limit);
        if (pageRequest != null && pageRequest.beforeCreatedAt() != null) {
            statement = statement
                    .param("beforeCreatedAt", Timestamp.from(pageRequest.beforeCreatedAt()))
                    .param("beforeId", pageRequest.beforeId().toString());
        }
        return statement.query((rs, rowNum) -> new AuditEvent(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("principal_id"),
                rs.getString("event_type"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant()
        )).list();
    }
}
