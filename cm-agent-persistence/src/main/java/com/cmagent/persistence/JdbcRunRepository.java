package com.cmagent.persistence;

import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.repository.RunRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 持久化 Agent 运行的状态、输入、输出和时间信息。 */
public class JdbcRunRepository implements RunRepository {
    private final JdbcClient jdbcClient;

    /**
     * 创建运行仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     */
    public JdbcRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    /**
     * 保存新建运行，并在写入前验证参数租户与领域对象一致。
     *
     * @param tenantId 当前租户标识
     * @param run 待保存的运行记录
     * @return 已保存的运行记录
     */
    public RunRecord save(UUID tenantId, RunRecord run) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(run, "run 不能为空");
        if (!tenantId.equals(run.tenantId())) {
            throw new IllegalArgumentException("tenantId 与 run.tenantId 不匹配");
        }

        jdbcClient.sql("""
                        INSERT INTO runs (
                            id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                            error_message, started_at, finished_at
                        ) VALUES (
                            :id, :tenantId, :agentId, :principalId, :status, :input, :output,
                            :errorMessage, :startedAt, :finishedAt
                        )
                        """)
                .param("id", run.id().toString())
                .param("tenantId", tenantId.toString())
                .param("agentId", run.agentId().toString())
                .param("principalId", run.principalId())
                .param("status", run.status().name())
                .param("input", run.input())
                .param("output", nullIfBlank(run.output()))
                .param("errorMessage", nullIfBlank(run.errorMessage()))
                .param("startedAt", Timestamp.from(run.startedAt()))
                .param("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
                .update();
        return run;
    }

    @Override
    /**
     * 将仍处于 {@code RUNNING} 的运行原子更新为最终状态。
     *
     * @param tenantId 当前租户标识
     * @param runId 待完成的运行标识
     * @param status 最终运行状态
     * @param output 模型输出，可为空白
     * @param errorMessage 失败说明，可为空白
     * @param finishedAt 完成时间
     * @return 更新后的运行记录
     */
    public RunRecord complete(
            UUID tenantId,
            UUID runId,
            RunStatus status,
            String output,
            String errorMessage,
            Instant finishedAt
    ) {
        RunRecord existing = findByTenantAndId(tenantId, runId)
                .orElseThrow(() -> new NoSuchElementException("Run 不存在"));
        RunRecord completed = existing.complete(status, output, errorMessage, finishedAt);

        int updated = jdbcClient.sql("""
                        UPDATE runs
                        SET status = :status,
                            output_text = :output,
                            error_message = :errorMessage,
                            finished_at = :finishedAt
                        WHERE tenant_id = :tenantId AND id = :runId AND status = 'RUNNING'
                        """)
                .param("status", completed.status().name())
                .param("output", nullIfBlank(completed.output()))
                .param("errorMessage", nullIfBlank(completed.errorMessage()))
                .param("finishedAt", Timestamp.from(completed.finishedAt()))
                .param("tenantId", tenantId.toString())
                .param("runId", runId.toString())
                .update();
        if (updated != 1) {
            throw new NoSuchElementException("Run 不存在");
        }
        return completed;
    }

    @Override
    /**
     * 在租户和 Agent 双重边界内查询单条运行。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param runId 运行标识
     * @return 匹配的运行记录
     */
    public Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                               error_message, started_at, finished_at
                        FROM runs
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND id = :runId
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("runId", runId.toString())
                .query(this::mapRun)
                .optional();
    }

    @Override
    /**
     * 使用“开始时间 + ID”复合游标倒序查询 Agent 的运行历史。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param pageRequest 游标位置和本页容量
     * @return 当前页运行记录
     */
    public List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        if (pageRequest.beforeStartedAt() == null) {
            return jdbcClient.sql("""
                            SELECT id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                                   error_message, started_at, finished_at
                            FROM runs
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                            ORDER BY started_at DESC, id DESC
                            LIMIT :limit
                            """)
                    .param("tenantId", tenantId.toString())
                    .param("agentId", agentId.toString())
                    .param("limit", pageRequest.limit())
                    .query(this::mapRun)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                               error_message, started_at, finished_at
                        FROM runs
                        WHERE tenant_id = :tenantId
                          AND agent_id = :agentId
                          AND (started_at < :beforeStartedAt
                               OR (started_at = :beforeStartedAt AND id < :beforeId))
                        ORDER BY started_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("beforeStartedAt", Timestamp.from(pageRequest.beforeStartedAt()))
                .param("beforeId", pageRequest.beforeId().toString())
                .param("limit", pageRequest.limit())
                .query(this::mapRun)
                .list();
    }

    /**
     * 在租户边界内查询运行，供完成状态前读取当前记录。
     *
     * @param tenantId 租户标识
     * @param runId 运行标识
     * @return 匹配的运行记录
     */
    private Optional<RunRecord> findByTenantAndId(UUID tenantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                               error_message, started_at, finished_at
                        FROM runs
                        WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId.toString())
                .param("runId", runId.toString())
                .query(this::mapRun)
                .optional();
    }

    /**
     * 将当前结果集行转换为运行领域对象，并处理可空完成时间。
     *
     * @param resultSet 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return 运行领域对象
     * @throws SQLException 读取列值失败时抛出
     */
    private RunRecord mapRun(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp finishedAt = resultSet.getTimestamp("finished_at");
        return new RunRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                resultSet.getString("principal_id"),
                RunStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("input_text"),
                resultSet.getString("output_text"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant()
        );
    }

    /**
     * 将空白输出或错误信息规范化为数据库 {@code NULL}。
     *
     * @param value 待规范化文本
     * @return 空白时返回 {@code null}，否则返回原值
     */
    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
