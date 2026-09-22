package com.cmagent.persistence;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.repository.SkillDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 保存技能稳定定义，并以可信租户条件限制全部读写。 */
public class JdbcSkillDefinitionRepository implements SkillDefinitionRepository {
    private static final String COLUMNS = """
            id, tenant_id, name, candidate_version_id, published_version_id,
            dependency_mapping_revision, enabled, access_epoch,
            created_by, updated_by, created_at, updated_at
            """;
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public Optional<SkillDefinition> find(UUID tenantId, UUID skillId) {
        return queryOne("WHERE tenant_id = :tenantId AND id = :skillId", tenantId, skillId, null);
    }

    @Override
    public Optional<SkillDefinition> findByName(UUID tenantId, String name) {
        return queryOne("WHERE tenant_id = :tenantId AND name = :name", tenantId, null, name);
    }

    @Override
    public ApiPageResponse<SkillDefinition> list(
            UUID tenantId, String query, Boolean enabled, ApiPageRequest page) {
        Objects.requireNonNull(page, "page 不能为空");
        String normalized = query == null ? "" : query.trim().toLowerCase();
        String enabledSql = enabled == null ? "" : " AND enabled = :enabled";
        long total = count(tenantId, normalized, enabled, enabledSql);
        var spec = jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_definitions "
                        + "WHERE tenant_id = :tenantId AND LOWER(name) LIKE :query" + enabledSql
                        + " ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("tenantId", tenantId.toString())
                .param("query", "%" + normalized + "%")
                .param("limit", page.size())
                .param("offset", page.page() * page.size());
        if (enabled != null) {
            spec = spec.param("enabled", enabled);
        }
        return new ApiPageResponse<>(spec.query(this::map).list(), total, page.page(), page.size());
    }

    @Override
    public SkillDefinition insert(SkillDefinition definition) {
        Objects.requireNonNull(definition, "definition 不能为空");
        jdbcClient.sql("""
                        INSERT INTO skill_definitions (
                            id, tenant_id, name, candidate_version_id, published_version_id,
                            dependency_mapping_revision, enabled, access_epoch,
                            created_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, :name, :candidateVersionId, :publishedVersionId,
                            :dependencyMappingRevision, :enabled, :accessEpoch,
                            :createdBy, :updatedBy, :createdAt, :updatedAt
                        )
                        """)
                .param("id", definition.id().toString())
                .param("tenantId", definition.tenantId().toString())
                .param("name", definition.name())
                .param("candidateVersionId", definition.candidateVersionId() == null ? null : definition.candidateVersionId().toString())
                .param("publishedVersionId", definition.publishedVersionId() == null ? null : definition.publishedVersionId().toString())
                .param("dependencyMappingRevision", definition.dependencyMappingRevision())
                .param("enabled", definition.enabled())
                .param("accessEpoch", definition.accessEpoch())
                .param("createdBy", definition.createdBy())
                .param("updatedBy", definition.updatedBy())
                .param("createdAt", Timestamp.from(definition.createdAt()))
                .param("updatedAt", Timestamp.from(definition.updatedAt()))
                .update();
        return definition;
    }

    @Override
    public SkillDefinition lock(UUID tenantId, UUID skillId) {
        // FOR UPDATE 只对当前事务有效；调用服务必须通过 SkillUnitOfWork 进入该方法。
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_definitions "
                        + "WHERE tenant_id = :tenantId AND id = :skillId FOR UPDATE")
                .param("tenantId", tenantId.toString())
                .param("skillId", skillId.toString())
                .query(this::map).optional()
                .orElseThrow(() -> new NoSuchElementException("技能不存在"));
    }

    @Override
    public boolean updateCurrent(SkillDefinition next, UUID expectedVersionId) {
        Objects.requireNonNull(next, "next 不能为空");
        return jdbcClient.sql("""
                        UPDATE skill_definitions
                        SET candidate_version_id = :candidateVersionId,
                            updated_by = :updatedBy, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :skillId
                          AND candidate_version_id = :expectedVersionId
                        """)
                .param("candidateVersionId", next.candidateVersionId() == null ? null : next.candidateVersionId().toString())
                .param("publishedVersionId", next.publishedVersionId() == null ? null : next.publishedVersionId().toString())
                .param("updatedBy", next.updatedBy())
                .param("updatedAt", Timestamp.from(next.updatedAt()))
                .param("tenantId", next.tenantId().toString())
                .param("skillId", next.id().toString())
                .param("expectedVersionId", expectedVersionId.toString())
                .update() == 1;
    }

    /** 使用候选和发布双指针做 CAS 更新。 */
    @Override
    public boolean updatePointers(SkillDefinition next, UUID expectedCandidateId, UUID expectedPublishedId) {
        return jdbcClient.sql("""
                        UPDATE skill_definitions
                        SET candidate_version_id = :candidateVersionId,
                            published_version_id = :publishedVersionId,
                            updated_by = :updatedBy, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :skillId
                          AND candidate_version_id = :expectedCandidateId
                          AND published_version_id = :expectedPublishedId
                        """)
                .param("candidateVersionId", next.candidateVersionId() == null ? null : next.candidateVersionId().toString())
                .param("publishedVersionId", next.publishedVersionId() == null ? null : next.publishedVersionId().toString())
                .param("updatedBy", next.updatedBy())
                .param("updatedAt", Timestamp.from(next.updatedAt()))
                .param("tenantId", next.tenantId().toString())
                .param("skillId", next.id().toString())
                .param("expectedCandidateId", expectedCandidateId == null ? null : expectedCandidateId.toString())
                .param("expectedPublishedId", expectedPublishedId == null ? null : expectedPublishedId.toString())
                .update() == 1;
    }

    /** 在同一事务中递增映射修订，防止预检和后续发布使用不同环境。 */
    @Override
    public boolean updateDependencyMappingRevision(SkillDefinition next, long expectedRevision) {
        return jdbcClient.sql("""
                        UPDATE skill_definitions
                        SET dependency_mapping_revision = :dependencyMappingRevision,
                            updated_by = :updatedBy, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :skillId
                          AND dependency_mapping_revision = :expectedRevision
                        """)
                .param("dependencyMappingRevision", next.dependencyMappingRevision())
                .param("updatedBy", next.updatedBy())
                .param("updatedAt", Timestamp.from(next.updatedAt()))
                .param("tenantId", next.tenantId().toString())
                .param("skillId", next.id().toString())
                .param("expectedRevision", expectedRevision)
                .update() == 1;
    }

    @Override
    public void updateEnabled(SkillDefinition next) {
        Objects.requireNonNull(next, "next 不能为空");
        int updated = jdbcClient.sql("""
                        UPDATE skill_definitions
                        SET enabled = :enabled, access_epoch = :accessEpoch,
                            updated_by = :updatedBy, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :skillId
                          AND candidate_version_id = :candidateVersionId
                          AND published_version_id = :publishedVersionId
                        """)
                .param("enabled", next.enabled())
                .param("accessEpoch", next.accessEpoch())
                .param("updatedBy", next.updatedBy())
                .param("updatedAt", Timestamp.from(next.updatedAt()))
                .param("tenantId", next.tenantId().toString())
                .param("skillId", next.id().toString())
                .param("candidateVersionId", next.candidateVersionId() == null ? null : next.candidateVersionId().toString())
                .param("publishedVersionId", next.publishedVersionId() == null ? null : next.publishedVersionId().toString())
                .update();
        if (updated != 1) {
            throw new NoSuchElementException("技能不存在或版本已变化");
        }
    }

    private long count(UUID tenantId, String query, Boolean enabled, String enabledSql) {
        var spec = jdbcClient.sql("SELECT COUNT(*) FROM skill_definitions "
                        + "WHERE tenant_id = :tenantId AND LOWER(name) LIKE :query" + enabledSql)
                .param("tenantId", tenantId.toString()).param("query", "%" + query + "%");
        if (enabled != null) {
            spec = spec.param("enabled", enabled);
        }
        return spec.query(Long.class).single();
    }

    private Optional<SkillDefinition> queryOne(
            String where, UUID tenantId, UUID skillId, String name) {
        var spec = jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_definitions " + where)
                .param("tenantId", tenantId.toString());
        if (skillId != null) {
            spec = spec.param("skillId", skillId.toString());
        }
        if (name != null) {
            spec = spec.param("name", name);
        }
        return spec.query(this::map).optional();
    }

    private SkillDefinition map(ResultSet resultSet, int rowNum) throws SQLException {
        String candidate = resultSet.getString("candidate_version_id");
        String published = resultSet.getString("published_version_id");
        return new SkillDefinition(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                resultSet.getString("name"),
                candidate == null ? null : UUID.fromString(candidate),
                published == null ? null : UUID.fromString(published),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("access_epoch"),
                resultSet.getLong("dependency_mapping_revision"),
                resultSet.getString("created_by"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
