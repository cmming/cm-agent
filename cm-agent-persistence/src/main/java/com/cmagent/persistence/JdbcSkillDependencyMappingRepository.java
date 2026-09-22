package com.cmagent.persistence;

import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 管理技能逻辑依赖映射；写入前必须锁定映射集合边界。 */
public class JdbcSkillDependencyMappingRepository implements SkillDependencyMappingRepository {
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillDependencyMappingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public Optional<SkillDependencyMapping> find(UUID tenantId, UUID skillId, String logicalKey) {
        return jdbcClient.sql("""
                SELECT tenant_id, skill_id, logical_key, tool_id, updated_by, updated_at
                FROM skill_dependency_mappings
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND logical_key = :logicalKey
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .param("logicalKey", logicalKey)
                .query(this::map).optional();
    }

    @Override
    public List<SkillDependencyMapping> list(UUID tenantId, UUID skillId) {
        return jdbcClient.sql("""
                SELECT tenant_id, skill_id, logical_key, tool_id, updated_by, updated_at
                FROM skill_dependency_mappings
                WHERE tenant_id = :tenantId AND skill_id = :skillId
                ORDER BY logical_key
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .query(this::map).list();
    }

    @Override
    public SkillDependencyMapping save(SkillDependencyMapping mapping) {
        Objects.requireNonNull(mapping, "mapping 不能为空");
        jdbcClient.sql("""
                DELETE FROM skill_dependency_mappings
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND logical_key = :logicalKey
                """)
                .param("tenantId", mapping.tenantId().toString())
                .param("skillId", mapping.skillId().toString())
                .param("logicalKey", mapping.logicalKey())
                .update();
        jdbcClient.sql("""
                INSERT INTO skill_dependency_mappings (
                    tenant_id, skill_id, logical_key, tool_id, updated_by, updated_at
                ) VALUES (
                    :tenantId, :skillId, :logicalKey, :toolId, :updatedBy, :updatedAt
                )
                """)
                .param("tenantId", mapping.tenantId().toString())
                .param("skillId", mapping.skillId().toString())
                .param("logicalKey", mapping.logicalKey())
                .param("toolId", mapping.toolId().toString())
                .param("updatedBy", mapping.updatedBy())
                .param("updatedAt", Timestamp.from(mapping.updatedAt()))
                .update();
        return mapping;
    }

    @Override
    public boolean delete(UUID tenantId, UUID skillId, String logicalKey) {
        return jdbcClient.sql("""
                DELETE FROM skill_dependency_mappings
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND logical_key = :logicalKey
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .param("logicalKey", logicalKey).update() == 1;
    }

    @Override
    public void lockSkill(UUID tenantId, UUID skillId) {
        jdbcClient.sql("""
                        SELECT id FROM skill_definitions
                        WHERE tenant_id = :tenantId AND id = :skillId FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("技能不存在"));
    }

    private SkillDependencyMapping map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillDependencyMapping(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                resultSet.getString("logical_key"),
                UUID.fromString(resultSet.getString("tool_id")),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
