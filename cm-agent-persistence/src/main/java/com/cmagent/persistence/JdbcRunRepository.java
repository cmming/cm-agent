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

public class JdbcRunRepository implements RunRepository {
    private final JdbcClient jdbcClient;

    public JdbcRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
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

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
