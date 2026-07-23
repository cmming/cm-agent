package com.cmagent.persistence;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcToolDefinitionRepository implements ToolDefinitionRepository {
    private final JdbcClient jdbcClient;

    public JdbcToolDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ToolDefinition save(ToolDefinition tool) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO tool_definitions (
                            id,
                            tenant_id,
                            name,
                            description,
                            type,
                            input_schema,
                            risk_level,
                            enabled,
                            endpoint,
                            created_by,
                            updated_by,
                            created_at,
                            updated_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            :name,
                            :description,
                            :type,
                            :inputSchema,
                            :riskLevel,
                            :enabled,
                            :endpoint,
                            :createdBy,
                            :updatedBy,
                            :createdAt,
                            :updatedAt
                        )
                        """)
                .param("id", tool.id().toString())
                .param("tenantId", tool.tenantId().toString())
                .param("name", tool.name())
                .param("description", tool.description())
                .param("type", tool.type().name())
                .param("inputSchema", tool.inputSchema())
                .param("riskLevel", tool.riskLevel().name())
                .param("enabled", tool.enabled())
                .param("endpoint", tool.endpoint())
                .param("createdBy", tool.createdBy())
                .param("updatedBy", tool.updatedBy())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return tool;
    }

    @Override
    public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT
                            id,
                            tenant_id,
                            name,
                            description,
                            type,
                            input_schema,
                            risk_level,
                            enabled,
                            endpoint,
                            created_by,
                            updated_by
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", toolId.toString())
                .query(this::mapTool)
                .optional();
    }

    @Override
    public List<ToolDefinition> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT
                            id,
                            tenant_id,
                            name,
                            description,
                            type,
                            input_schema,
                            risk_level,
                            enabled,
                            endpoint,
                            created_by,
                            updated_by
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId
                        ORDER BY name ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapTool)
                .list();
    }

    @Override
    public void delete(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_definitions WHERE tenant_id = :tenantId AND id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    private ToolDefinition mapTool(ResultSet rs, int rowNum) throws SQLException {
        return new ToolDefinition(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                ToolType.valueOf(rs.getString("type")),
                rs.getString("input_schema"),
                ToolRiskLevel.valueOf(rs.getString("risk_level")),
                rs.getBoolean("enabled"),
                rs.getString("endpoint"),
                rs.getString("created_by"),
                rs.getString("updated_by")
        );
    }
}
