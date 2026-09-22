package com.cmagent.persistence;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightItemStatus;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.repository.SkillPreflightRepository;
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

/** 使用事务保存预检汇总和全部明细；读取时必须同时限定租户。 */
public final class JdbcSkillPreflightRepository implements SkillPreflightRepository {
    private static final String CHECK_COLUMNS = """
            id, tenant_id, skill_id, version_id, mapping_revision, scope,
            agent_id, status, created_by, created_at
            """;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcClient          执行具名参数 SQL 的客户端
     * @param transactionTemplate 覆盖汇总和明细写入的短事务边界
     */
    public JdbcSkillPreflightRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public SkillPreflightCheck save(SkillPreflightCheck check, List<SkillPreflightItem> items) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            jdbcClient.sql("""
                    INSERT INTO skill_preflight_checks (
                        id, tenant_id, skill_id, version_id, mapping_revision, scope,
                        agent_id, status, created_by, created_at
                    ) VALUES (
                        :id, :tenantId, :skillId, :versionId, :mappingRevision, :scope,
                        :agentId, :status, :createdBy, :createdAt
                    )
                    """)
                    .param("id", check.id().toString()).param("tenantId", check.tenantId().toString())
                    .param("skillId", check.skillId().toString())
                    .param("versionId", check.versionId().toString())
                    .param("mappingRevision", check.mappingRevision())
                    .param("scope", check.scope().name())
                    .param("agentId", check.agentId() == null ? null : check.agentId().toString())
                    .param("status", check.status().name())
                    .param("createdBy", check.createdBy())
                    .param("createdAt", Timestamp.from(check.createdAt()))
                    .update();
            for (SkillPreflightItem item : items) {
                Objects.requireNonNull(item, "item 不能为空");
                jdbcClient.sql("""
                        INSERT INTO skill_preflight_items (
                            tenant_id, check_id, agent_key, agent_id, logical_key,
                            required, tool_id, status, error_code, message, error_id
                        ) VALUES (
                            :tenantId, :checkId, :agentKey, :agentId, :logicalKey,
                            :required, :toolId, :status, :errorCode, :message, :errorId
                        )
                        """)
                        .param("tenantId", check.tenantId().toString())
                        .param("checkId", item.checkId().toString())
                        .param("agentKey", agentKey(check, item))
                        .param("agentId", item.agentId() == null ? null : item.agentId().toString())
                        .param("logicalKey", item.logicalKey())
                        .param("required", item.required())
                        .param("toolId", item.toolId() == null ? null : item.toolId().toString())
                        .param("status", item.status().name())
                        .param("errorCode", item.errorCode() == null ? null : item.errorCode().name())
                        .param("message", item.message())
                        .param("errorId", item.errorId())
                        .update();
            }
            return check;
        }), "预检保存结果不能为空");
    }

    @Override
    public Optional<SkillPreflightCheck> find(UUID tenantId, UUID checkId) {
        return jdbcClient.sql("SELECT " + CHECK_COLUMNS + " FROM skill_preflight_checks "
                        + "WHERE tenant_id = :tenantId AND id = :checkId")
                .param("tenantId", tenantId.toString()).param("checkId", checkId.toString())
                .query(this::mapCheck).optional();
    }

    @Override
    public List<SkillPreflightItem> listItems(UUID tenantId, UUID checkId) {
        return jdbcClient.sql("""
                SELECT tenant_id, check_id, agent_id, logical_key, required, tool_id,
                       status, error_code, message, error_id
                FROM skill_preflight_items
                WHERE tenant_id = :tenantId AND check_id = :checkId
                ORDER BY agent_key, logical_key
                """)
                .param("tenantId", tenantId.toString()).param("checkId", checkId.toString())
                .query(this::mapItem).list();
    }

    @Override
    public List<SkillPreflightCheck> list(UUID tenantId, UUID skillId, UUID versionId) {
        return jdbcClient.sql("SELECT " + CHECK_COLUMNS + " FROM skill_preflight_checks "
                        + "WHERE tenant_id = :tenantId AND skill_id = :skillId AND version_id = :versionId "
                        + "ORDER BY created_at DESC, id DESC")
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .param("versionId", versionId.toString())
                .query(this::mapCheck).list();
    }

    private String agentKey(SkillPreflightCheck check, SkillPreflightItem item) {
        if (item.agentId() != null) {
            return item.agentId().toString();
        }
        UUID expected = (item.agentId() == null ? null : item.agentId());
        return expected == null ? check.tenantId().toString() : expected.toString();
    }

    private SkillPreflightCheck mapCheck(ResultSet resultSet, int rowNum) throws SQLException {
        UUID agentId = optionalUuid(resultSet, "agent_id");
        return new SkillPreflightCheck(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                UUID.fromString(resultSet.getString("version_id")),
                resultSet.getLong("mapping_revision"),
                SkillPreflightScope.valueOf(resultSet.getString("scope")),
                agentId,
                SkillPreflightStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private SkillPreflightItem mapItem(ResultSet resultSet, int rowNum) throws SQLException {
        String errorCode = resultSet.getString("error_code");
        String errorId = resultSet.getString("error_id");
        return new SkillPreflightItem(
                UUID.fromString(resultSet.getString("check_id")),
                optionalUuid(resultSet, "agent_id"),
                resultSet.getString("logical_key"),
                resultSet.getBoolean("required"),
                optionalUuid(resultSet, "tool_id"),
                SkillPreflightItemStatus.valueOf(resultSet.getString("status")),
                errorCode == null ? null : ApiErrorCode.valueOf(errorCode),
                resultSet.getString("message"),
                errorId == null ? null : errorId);
    }

    private UUID optionalUuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    /** @return 指定租户和标识对应的预检；不存在时抛出受控异常。 */
    public SkillPreflightCheck lock(UUID tenantId, UUID checkId) {
        return jdbcClient.sql("SELECT " + CHECK_COLUMNS + " FROM skill_preflight_checks "
                        + "WHERE tenant_id = :tenantId AND id = :checkId FOR UPDATE")
                .param("tenantId", tenantId.toString()).param("checkId", checkId.toString())
                .query(this::mapCheck).optional()
                .orElseThrow(() -> new NoSuchElementException("技能预检不存在"));
    }
}
