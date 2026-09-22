package com.cmagent.persistence;

import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.domain.SkillReleaseAction;
import com.cmagent.core.repository.SkillReleaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 保存技能发布历史，并在工作单元内锁定最新发布事实。 */
public class JdbcSkillReleaseRepository implements SkillReleaseRepository {
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillReleaseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public SkillRelease insert(SkillRelease release) {
        Objects.requireNonNull(release, "release 不能为空");
        jdbcClient.sql("""
                INSERT INTO skill_releases (
                    id, tenant_id, skill_id, release_no, action, previous_version_id,
                    version_id, preflight_id, trial_run_id, created_by, created_at
                ) VALUES (
                    :id, :tenantId, :skillId, :releaseNo, :action, :previousVersionId,
                    :versionId, :preflightId, :trialRunId, :createdBy, :createdAt
                )
                """)
                .param("id", release.id().toString())
                .param("tenantId", release.tenantId().toString())
                .param("skillId", release.skillId().toString())
                .param("releaseNo", release.releaseNo())
                .param("action", release.action().name())
                .param("previousVersionId", release.previousVersionId() == null ? null : release.previousVersionId().toString())
                .param("versionId", release.versionId().toString())
                .param("preflightId", release.preflightId() == null ? null : release.preflightId().toString())
                .param("trialRunId", release.trialRunId() == null ? null : release.trialRunId().toString())
                .param("createdBy", release.createdBy())
                .param("createdAt", Timestamp.from(release.createdAt()))
                .update();
        return release;
    }

    @Override
    public Optional<SkillRelease> find(UUID tenantId, UUID releaseId) {
        return jdbcClient.sql("""
                SELECT id, tenant_id, skill_id, release_no, action, previous_version_id,
                       version_id, preflight_id, trial_run_id, created_by, created_at
                FROM skill_releases
                WHERE tenant_id = :tenantId AND id = :releaseId
                """)
                .param("tenantId", tenantId.toString()).param("releaseId", releaseId.toString())
                .query(this::map).optional();
    }

    @Override
    public List<SkillRelease> list(UUID tenantId, UUID skillId) {
        return jdbcClient.sql("""
                SELECT id, tenant_id, skill_id, release_no, action, previous_version_id,
                       version_id, preflight_id, trial_run_id, created_by, created_at
                FROM skill_releases
                WHERE tenant_id = :tenantId AND skill_id = :skillId
                ORDER BY release_no DESC, id DESC
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .query(this::map).list();
    }

    @Override
    public Optional<SkillRelease> lockLatest(UUID tenantId, UUID skillId) {
        return jdbcClient.sql("""
                SELECT id, tenant_id, skill_id, release_no, action, previous_version_id,
                       version_id, preflight_id, trial_run_id, created_by, created_at
                FROM skill_releases
                WHERE tenant_id = :tenantId AND skill_id = :skillId
                ORDER BY release_no DESC, id DESC
                LIMIT 1 FOR UPDATE
                """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .query(this::map).optional();
    }

    private SkillRelease map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillRelease(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                resultSet.getLong("release_no"),
                SkillReleaseAction.valueOf(resultSet.getString("action")),
                optionalUuid(resultSet, "previous_version_id"),
                UUID.fromString(resultSet.getString("version_id")),
                optionalUuid(resultSet, "preflight_id"),
                optionalUuid(resultSet, "trial_run_id"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private UUID optionalUuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : UUID.fromString(value);
    }
}
