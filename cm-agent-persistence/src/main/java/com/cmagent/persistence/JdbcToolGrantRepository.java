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

/** 使用 JDBC 持久化 Agent 与工具之间的租户级授权关系。 */
public class JdbcToolGrantRepository implements ToolGrantRepository {
    private final JdbcClient jdbcClient;

    /**
     * 创建工具授权仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     */
    public JdbcToolGrantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    /**
     * 新增或覆盖一条工具授权，并保持授权对象中的租户边界。
     *
     * @param grant 待持久化的授权定义
     * @return 已保存的授权定义
     */
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
    /**
     * 查询指定租户的全部工具授权。
     *
     * @param tenantId 租户标识
     * @return 该租户的授权列表
     */
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
    /**
     * 查询指定租户中某个 Agent 的全部工具授权。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @return 匹配的授权列表
     */
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
    /**
     * 查询租户、Agent 与工具三者完全匹配的授权。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param toolId 工具标识
     * @return 匹配的授权列表
     */
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

    @Override
    /**
     * 删除指定 Agent 对某个工具的授权。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param toolId 工具标识
     */
    public void delete(UUID tenantId, UUID agentId, UUID toolId) {
        jdbcClient.sql("""
                        DELETE FROM tool_grants
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND tool_id = :toolId
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    @Override
    /**
     * 删除租户内引用指定工具的全部授权，供工具删除流程使用。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     */
    public void deleteByTenantAndToolId(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_grants WHERE tenant_id = :tenantId AND tool_id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    /**
     * 将当前结果集行转换为工具授权领域对象。
     *
     * @param rs 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return 工具授权领域对象
     * @throws SQLException 读取列值失败时抛出
     */
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
