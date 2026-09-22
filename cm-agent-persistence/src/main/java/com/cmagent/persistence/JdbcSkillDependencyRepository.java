package com.cmagent.persistence;

import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.repository.SkillDependencyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 JDBC 保存技能版本内的依赖声明；版本和租户条件始终一起出现。 */
public class JdbcSkillDependencyRepository implements SkillDependencyRepository {
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillDependencyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public void insertAll(List<SkillDependency> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies 不能为空");
        for (SkillDependency dependency : dependencies) {
            Objects.requireNonNull(dependency, "dependency 不能为空");
            jdbcClient.sql("""
                    INSERT INTO skill_dependencies (
                        tenant_id, skill_id, version_id, logical_key, required,
                        description, position
                    ) VALUES (
                        :tenantId, :skillId, :versionId, :logicalKey, :required,
                        :description, :position
                    )
                    """)
                    .param("tenantId", dependency.tenantId().toString())
                    .param("skillId", dependency.skillId().toString())
                    .param("versionId", dependency.versionId().toString())
                    .param("logicalKey", dependency.logicalKey())
                    .param("required", dependency.required())
                    .param("description", dependency.description())
                    .param("position", dependency.position())
                    .update();
        }
    }

    @Override
    public List<SkillDependency> list(UUID tenantId, UUID skillId, UUID versionId) {
        return jdbcClient.sql("""
                SELECT tenant_id, skill_id, version_id, logical_key, required,
                       description, position
                FROM skill_dependencies
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND version_id = :versionId
                ORDER BY position, logical_key
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .param("versionId", versionId.toString())
                .query(this::map).list();
    }

    private SkillDependency map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillDependency(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                UUID.fromString(resultSet.getString("version_id")),
                resultSet.getString("logical_key"),
                resultSet.getBoolean("required"),
                resultSet.getString("description"),
                resultSet.getInt("position"));
    }
}
