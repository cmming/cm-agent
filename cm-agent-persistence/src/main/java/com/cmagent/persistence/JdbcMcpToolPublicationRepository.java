package com.cmagent.persistence;

import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.repository.McpToolPublicationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public class JdbcMcpToolPublicationRepository implements McpToolPublicationRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcMcpToolPublicationRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public McpToolPublication save(McpToolPublication publication) {
        return transactionTemplate.execute(status -> saveWithinTransaction(publication));
    }

    private McpToolPublication saveWithinTransaction(McpToolPublication publication) {
        lockToolDefinition(publication.tenantId(), publication.toolId());
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

    private void lockToolDefinition(UUID tenantId, UUID toolId) {
        boolean exists = jdbcClient.sql("""
                        SELECT id
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :toolId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query((resultSet, rowNum) -> resultSet.getString("id"))
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("MCP 工具不存在或不属于当前租户");
        }
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
    public Map<UUID, McpToolPublication> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        List<McpToolPublication> publications = jdbcClient.sql("""
                        SELECT tenant_id, tool_id, enabled, published_by
                        FROM tool_mcp_publications
                        WHERE tenant_id = :tenantId AND tool_id IN (:toolIds)
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolIds", toolIds.stream().map(UUID::toString).toList())
                .query(this::mapPublication)
                .list();
        Map<UUID, McpToolPublication> byToolId = new LinkedHashMap<>();
        publications.forEach(publication -> byToolId.put(publication.toolId(), publication));
        return Map.copyOf(byToolId);
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
