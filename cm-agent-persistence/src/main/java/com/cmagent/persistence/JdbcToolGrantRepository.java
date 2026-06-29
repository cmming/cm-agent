package com.cmagent.persistence;

import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.ToolGrantRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcToolGrantRepository implements ToolGrantRepository {
    private final JdbcClient jdbcClient;

    public JdbcToolGrantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ToolGrant save(ToolGrant grant) {
        List<ToolGrant> existing = listByTenantAgentAndTool(grant.tenantId(), grant.agentId(), grant.toolId());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        jdbcClient.sql("""
                        INSERT INTO tool_grants (
                            id,
                            tenant_id,
                            tool_id,
                            agent_id,
                            role_code,
                            granted,
                            created_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            :toolId,
                            :agentId,
                            :roleCode,
                            :granted,
                            :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", grant.tenantId().toString())
                .param("toolId", grant.toolId().toString())
                .param("agentId", grant.agentId().toString())
                .param("roleCode", grant.roleCode())
                .param("granted", grant.granted())
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return grant;
    }

    @Override
    public List<ToolGrant> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapGrant)
                .list();
    }

    @Override
    public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .query(this::mapGrant)
                .list();
    }

    @Override
    public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND tool_id = :toolId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapGrant)
                .list();
    }

    private ToolGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        return new ToolGrant(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("tool_id")),
                UUID.fromString(rs.getString("agent_id")),
                rs.getString("role_code"),
                rs.getBoolean("granted")
        );
    }
}
