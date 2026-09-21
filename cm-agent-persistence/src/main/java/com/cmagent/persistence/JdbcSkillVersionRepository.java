package com.cmagent.persistence;

import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.SkillVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 追加不可变技能版本；既有版本不提供更新入口。 */
public class JdbcSkillVersionRepository implements SkillVersionRepository {
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbcClient 执行具名参数 SQL 的客户端
     * @param objectMapper 只序列化受领域对象约束的 JSON 等价元数据
     */
    public JdbcSkillVersionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public void insert(SkillVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        jdbcClient.sql("""
                        INSERT INTO skill_versions (
                            id, tenant_id, skill_id, version_no, description, metadata_json,
                            skill_content, sha256, created_by, created_at
                        ) VALUES (
                            :id, :tenantId, :skillId, :versionNo, :description, :metadataJson,
                            :content, :sha256, :createdBy, :createdAt
                        )
                        """)
                .param("id", version.id().toString())
                .param("tenantId", version.tenantId().toString())
                .param("skillId", version.skillId().toString())
                .param("versionNo", version.versionNo())
                .param("description", version.description())
                .param("metadataJson", writeJson(version.metadata()))
                .param("content", version.content())
                .param("sha256", version.sha256())
                .param("createdBy", version.createdBy())
                .param("createdAt", Timestamp.from(version.createdAt()))
                .update();
    }

    @Override
    public Optional<SkillVersion> find(UUID tenantId, UUID skillId, UUID versionId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, skill_id, version_no, description, metadata_json,
                               skill_content, sha256, created_by, created_at
                        FROM skill_versions
                        WHERE tenant_id = :tenantId AND skill_id = :skillId AND id = :versionId
                        """)
                .param("tenantId", tenantId.toString())
                .param("skillId", skillId.toString())
                .param("versionId", versionId.toString())
                .query(this::map).optional();
    }

    private SkillVersion map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillVersion(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                resultSet.getInt("version_no"),
                resultSet.getString("description"),
                readMetadata(resultSet.getString("metadata_json")),
                resultSet.getString("skill_content"),
                resultSet.getString("sha256"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("技能元数据序列化失败", exception);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("持久化技能元数据无法解析", exception);
        }
    }
}
