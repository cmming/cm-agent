package com.cmagent.persistence;

import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.repository.SkillResourceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 JDBC 追加并读取技能版本的 UTF-8 文本资源。 */
public class JdbcSkillResourceRepository implements SkillResourceRepository {
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillResourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public void insertAll(List<SkillResource> resources) {
        List.copyOf(Objects.requireNonNull(resources, "resources 不能为空")).forEach(this::insert);
    }

    @Override
    public List<SkillResource> list(UUID tenantId, UUID skillId, UUID versionId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, skill_id, version_id, path, media_type, content, byte_length, sha256
                        FROM skill_resources
                        WHERE tenant_id = :tenantId AND skill_id = :skillId AND version_id = :versionId
                        ORDER BY path
                        """)
                .param("tenantId", tenantId.toString())
                .param("skillId", skillId.toString())
                .param("versionId", versionId.toString())
                .query(this::map).list();
    }

    private void insert(SkillResource resource) {
        jdbcClient.sql("""
                        INSERT INTO skill_resources (
                            tenant_id, skill_id, version_id, path, media_type, content, byte_length, sha256
                        ) VALUES (
                            :tenantId, :skillId, :versionId, :path, :mediaType, :content, :byteLength, :sha256
                        )
                        """)
                .param("tenantId", resource.tenantId().toString())
                .param("skillId", resource.skillId().toString())
                .param("versionId", resource.versionId().toString())
                .param("path", resource.path())
                .param("mediaType", resource.mediaType())
                .param("content", resource.content())
                .param("byteLength", resource.byteLength())
                .param("sha256", resource.sha256())
                .update();
    }

    private SkillResource map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillResource(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                UUID.fromString(resultSet.getString("version_id")),
                resultSet.getString("path"),
                resultSet.getString("media_type"),
                resultSet.getString("content"),
                resultSet.getInt("byte_length"),
                resultSet.getString("sha256"));
    }
}
