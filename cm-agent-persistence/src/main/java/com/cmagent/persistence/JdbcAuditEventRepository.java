package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

public class JdbcAuditEventRepository implements AuditEventRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuditEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        return jdbcClient.sql("""
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
                        """)
                .param("tenantId", tenantId.toString())
                .param("limit", limit)
                .query((rs, rowNum) -> new AuditEvent(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getString("principal_id"),
                        rs.getString("event_type"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("status"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }
}
