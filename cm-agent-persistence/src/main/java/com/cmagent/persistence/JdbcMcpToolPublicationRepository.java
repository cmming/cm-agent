package com.cmagent.persistence;

import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public class JdbcMcpToolPublicationRepository implements McpToolPublicationRepository {
    private final JdbcClient jdbcClient;

    public JdbcMcpToolPublicationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public McpToolPublication save(McpToolPublication publication) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcClient.sql("""
                        UPDATE tool_mcp_publications
                        SET enabled = :enabled,
                            published_by = :publishedBy,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("enabled", publication.enabled())
                .param("publishedBy", publication.publishedBy())
                .param("updatedAt", now)
                .param("tenantId", publication.tenantId().toString())
                .param("toolId", publication.toolId().toString())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO tool_mcp_publications (
                                tenant_id, tool_id, enabled, published_by, created_at, updated_at
                            ) VALUES (
                                :tenantId, :toolId, :enabled, :publishedBy, :createdAt, :updatedAt
                            )
                            """)
                    .param("tenantId", publication.tenantId().toString())
                    .param("toolId", publication.toolId().toString())
                    .param("enabled", publication.enabled())
                    .param("publishedBy", publication.publishedBy())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        }
        return publication;
    }

    @Override
    public Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, enabled, published_by
                        FROM tool_mcp_publications
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapPublication)
                .optional();
    }

    @Override
    public List<McpToolPublication> listEnabledByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, enabled, published_by
                        FROM tool_mcp_publications
                        WHERE tenant_id = :tenantId AND enabled = true
                        ORDER BY tool_id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapPublication)
                .list();
    }

    @Override
    public void delete(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_mcp_publications WHERE tenant_id = :tenantId AND tool_id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    private McpToolPublication mapPublication(ResultSet resultSet, int rowNum) throws SQLException {
        return new McpToolPublication(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("tool_id")),
                resultSet.getBoolean("enabled"),
                resultSet.getString("published_by")
        );
    }
}
