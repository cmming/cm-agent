package com.cmagent.persistence;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 追加技能读取尝试，并按模型调用标识提供幂等查询。 */
public class JdbcSkillLoadRecordRepository implements SkillLoadRecordRepository {
    private static final String COLUMNS = """
            id, tenant_id, run_id, model_call_id, attempt_no, skill_id, version_id, path,
            status, delivered_bytes, duration_millis, error_code, error_id, created_at
            """;
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcSkillLoadRecordRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public void insert(SkillLoadRecord record) {
        Objects.requireNonNull(record, "record 不能为空");
        jdbcClient.sql("""
                        INSERT INTO skill_load_records (
                            id, tenant_id, run_id, model_call_id, attempt_no, skill_id, version_id, path,
                            status, delivered_bytes, duration_millis, error_code, error_id, created_at
                        ) VALUES (
                            :id, :tenantId, :runId, :modelCallId, :attemptNo, :skillId, :versionId, :path,
                            :status, :deliveredBytes, :durationMillis, :errorCode, :errorId, :createdAt
                        )
                        """)
                .param("id", record.id().toString())
                .param("tenantId", record.tenantId().toString())
                .param("runId", record.runId().toString())
                .param("modelCallId", record.modelCallId())
                .param("attemptNo", record.attemptNo())
                .param("skillId", record.skillId() == null ? null : record.skillId().toString())
                .param("versionId", record.versionId() == null ? null : record.versionId().toString())
                .param("path", record.path())
                .param("status", record.status().name())
                .param("deliveredBytes", record.deliveredBytes())
                .param("durationMillis", record.durationMillis())
                .param("errorCode", record.errorCode() == null ? null : record.errorCode().name())
                .param("errorId", blankToNull(record.errorId()))
                .param("createdAt", Timestamp.from(record.createdAt()))
                .update();
    }

    @Override
    public Optional<SkillLoadRecord> findByCall(UUID tenantId, UUID runId, String modelCallId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_load_records "
                        + "WHERE tenant_id = :tenantId AND run_id = :runId AND model_call_id = :modelCallId")
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .param("modelCallId", modelCallId).query(this::map).optional();
    }

    @Override
    public List<SkillLoadRecord> listForBudget(UUID tenantId, UUID runId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_load_records "
                        + "WHERE tenant_id = :tenantId AND run_id = :runId ORDER BY attempt_no, id")
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .query(this::map).list();
    }

    @Override
    public ApiPageResponse<SkillLoadRecord> list(UUID tenantId, UUID runId, ApiPageRequest page) {
        Objects.requireNonNull(page, "page 不能为空");
        long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM skill_load_records
                        WHERE tenant_id = :tenantId AND run_id = :runId
                        """)
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .query(Long.class).single();
        List<SkillLoadRecord> items = jdbcClient.sql("SELECT " + COLUMNS + " FROM skill_load_records "
                        + "WHERE tenant_id = :tenantId AND run_id = :runId "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .param("limit", page.size()).param("offset", page.page() * page.size())
                .query(this::map).list();
        return new ApiPageResponse<>(items, total, page.page(), page.size());
    }

    private SkillLoadRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        String skillId = resultSet.getString("skill_id");
        String versionId = resultSet.getString("version_id");
        String errorCode = resultSet.getString("error_code");
        return new SkillLoadRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("run_id")),
                resultSet.getString("model_call_id"),
                resultSet.getInt("attempt_no"),
                skillId == null ? null : UUID.fromString(skillId),
                versionId == null ? null : UUID.fromString(versionId),
                resultSet.getString("path"),
                SkillLoadStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("delivered_bytes"),
                resultSet.getLong("duration_millis"),
                errorCode == null ? null : ApiErrorCode.valueOf(errorCode),
                resultSet.getString("error_id"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
