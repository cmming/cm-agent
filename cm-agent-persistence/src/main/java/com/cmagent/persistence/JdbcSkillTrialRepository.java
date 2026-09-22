package com.cmagent.persistence;

import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.repository.SkillTrialRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 记录指定技能版本试运行；runId 同时承担受控授权来源。 */
public final class JdbcSkillTrialRepository implements SkillTrialRepository {
    private static final String TRIAL_COLUMNS = """
            run_id, tenant_id, skill_id, version_id, agent_id, mapping_revision,
            status, qualifies_release, created_by, created_at, updated_at
            """;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcClient          执行具名参数 SQL 的客户端
     * @param transactionTemplate 保护条件状态更新可见性的短事务边界
     */
    public JdbcSkillTrialRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public SkillTrial insert(SkillTrial trial) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            jdbcClient.sql("""
                    INSERT INTO skill_trials (
                        run_id, tenant_id, skill_id, version_id, agent_id, mapping_revision,
                        status, qualifies_release, created_by, created_at, updated_at
                    ) VALUES (
                        :runId, :tenantId, :skillId, :versionId, :agentId, :mappingRevision,
                        :status, :qualifiesRelease, :createdBy, :createdAt, :updatedAt
                    )
                    """)
                    .param("runId", trial.runId().toString())
                    .param("tenantId", trial.tenantId().toString())
                    .param("skillId", trial.skillId().toString())
                    .param("versionId", trial.versionId().toString())
                    .param("agentId", trial.agentId().toString())
                    .param("mappingRevision", trial.mappingRevision())
                    .param("status", trial.status().name())
                    .param("qualifiesRelease", trial.qualifiesRelease())
                    .param("createdBy", trial.createdBy())
                    .param("createdAt", Timestamp.from(trial.createdAt()))
                    .param("updatedAt", Timestamp.from(trial.updatedAt()))
                    .update();
            return trial;
        }), "试运行保存结果不能为空");
    }

    @Override
    public Optional<SkillTrial> find(UUID tenantId, UUID runId) {
        return jdbcClient.sql("SELECT " + TRIAL_COLUMNS + " FROM skill_trials "
                        + "WHERE tenant_id = :tenantId AND run_id = :runId")
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .query(this::map).optional();
    }

    @Override
    public List<SkillTrial> list(UUID tenantId, UUID skillId, UUID versionId) {
        return jdbcClient.sql("SELECT " + TRIAL_COLUMNS + " FROM skill_trials "
                        + "WHERE tenant_id = :tenantId AND skill_id = :skillId AND version_id = :versionId "
                        + "ORDER BY created_at DESC, run_id DESC")
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .param("versionId", versionId.toString())
                .query(this::map).list();
    }

    @Override
    public boolean update(SkillTrial next, SkillTrialStatus expectedStatus) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            SkillTrial current = lock(next.tenantId(), next.runId());
            if (current == null || current.status() != expectedStatus) {
                return false;
            }
            int updated = jdbcClient.sql("""
                    UPDATE skill_trials
                    SET status = :status, qualifies_release = :qualifiesRelease,
                        created_by = :createdBy, created_at = :createdAt, updated_at = :updatedAt
                    WHERE tenant_id = :tenantId AND run_id = :runId
                      AND status = :expectedStatus
                    """)
                    .param("status", next.status().name())
                    .param("qualifiesRelease", next.qualifiesRelease())
                    .param("createdBy", next.createdBy())
                    .param("createdAt", Timestamp.from(next.createdAt()))
                    .param("updatedAt", Timestamp.from(next.updatedAt()))
                    .param("tenantId", next.tenantId().toString())
                    .param("runId", next.runId().toString())
                    .param("expectedStatus", expectedStatus.name())
                    .update();
            return updated == 1;
        }));
    }

    @Override
    public SkillTrial lock(UUID tenantId, UUID runId) {
        return jdbcClient.sql("SELECT " + TRIAL_COLUMNS + " FROM skill_trials "
                        + "WHERE tenant_id = :tenantId AND run_id = :runId FOR UPDATE")
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .query(this::map).optional()
                .orElseThrow(() -> new NoSuchElementException("技能试运行不存在"));
    }

    private SkillTrial map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillTrial(
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                UUID.fromString(resultSet.getString("version_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                resultSet.getLong("mapping_revision"),
                SkillTrialStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("qualifies_release"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
