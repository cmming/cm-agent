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

/** 使用 JDBC 维护工具的 MCP 发布状态。 */
public class JdbcMcpToolPublicationRepository implements McpToolPublicationRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建 MCP 工具发布仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     * @param transactionTemplate 保证工具锁定与发布状态写入原子性的事务模板
     */
    public JdbcMcpToolPublicationRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    /**
     * 在事务中保存工具的 MCP 发布状态。
     *
     * @param publication 待保存的发布定义
     * @return 已保存的发布定义
     */
    public McpToolPublication save(McpToolPublication publication) {
        return transactionTemplate.execute(status -> saveWithinTransaction(publication));
    }

    /**
     * 锁定所属工具后插入或更新发布状态，避免与工具删除并发交错。
     *
     * @param publication 待保存的发布定义
     * @return 已保存的发布定义
     */
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

    /**
     * 按租户锁定所属工具，并拒绝不存在或已删除的工具。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     */
    private void lockToolDefinition(UUID tenantId, UUID toolId) {
        boolean exists = jdbcClient.sql("""
                        SELECT id
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :toolId AND deleted_at IS NULL
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
    /**
     * 查询租户内单个工具的 MCP 发布状态。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     * @return 匹配的发布定义
     */
    public Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT publication.tenant_id, publication.tool_id,
                               publication.enabled, publication.published_by
                        FROM tool_mcp_publications publication
                        INNER JOIN tool_definitions tool
                            ON tool.id = publication.tool_id AND tool.tenant_id = publication.tenant_id
                        WHERE publication.tenant_id = :tenantId
                          AND publication.tool_id = :toolId
                          AND tool.deleted_at IS NULL
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapPublication)
                .optional();
    }

    @Override
    /**
     * 批量查询租户内工具发布状态，并以工具 ID 建立索引。
     *
     * @param tenantId 租户标识
     * @param toolIds 待查询的工具标识集合
     * @return 工具 ID 到发布定义的映射
     */
    public Map<UUID, McpToolPublication> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        List<McpToolPublication> publications = jdbcClient.sql("""
                        SELECT publication.tenant_id, publication.tool_id,
                               publication.enabled, publication.published_by
                        FROM tool_mcp_publications publication
                        INNER JOIN tool_definitions tool
                            ON tool.id = publication.tool_id AND tool.tenant_id = publication.tenant_id
                        WHERE publication.tenant_id = :tenantId
                          AND publication.tool_id IN (:toolIds)
                          AND tool.deleted_at IS NULL
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
    /**
     * 查询租户内所有当前启用的 MCP 工具发布记录。
     *
     * @param tenantId 租户标识
     * @return 已启用发布记录列表
     */
    public List<McpToolPublication> listEnabledByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT publication.tenant_id, publication.tool_id,
                               publication.enabled, publication.published_by
                        FROM tool_mcp_publications publication
                        INNER JOIN tool_definitions tool
                            ON tool.id = publication.tool_id AND tool.tenant_id = publication.tenant_id
                        WHERE publication.tenant_id = :tenantId
                          AND publication.enabled = true
                          AND tool.deleted_at IS NULL
                        ORDER BY publication.tool_id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapPublication)
                .list();
    }

    @Override
    /**
     * 删除租户内指定工具的 MCP 发布记录。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     */
    public void delete(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_mcp_publications WHERE tenant_id = :tenantId AND tool_id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    /**
     * 将结果集行转换为 MCP 工具发布领域对象。
     *
     * @param resultSet 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return MCP 工具发布领域对象
     * @throws SQLException 读取列值失败时抛出
     */
    private McpToolPublication mapPublication(ResultSet resultSet, int rowNum) throws SQLException {
        return new McpToolPublication(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("tool_id")),
                resultSet.getBoolean("enabled"),
                resultSet.getString("published_by")
        );
    }
}
