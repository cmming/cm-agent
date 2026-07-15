package com.cmagent.persistence;

import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.repository.ToolCallRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

public class JdbcToolCallRepository implements ToolCallRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcToolCallRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void saveAll(UUID tenantId, RunToolCallBatch toolCalls) {
        toolCalls.requireTenant(tenantId);
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            for (RunToolCall toolCall : toolCalls.toolCalls()) {
                jdbcClient.sql("""
                            INSERT INTO tool_calls (
                                id, tenant_id, run_id, tool_id, tool_name, input_summary, output_summary,
                                status, authorized, duration_ms, error_message, created_at
                            ) VALUES (
                                :id, :tenantId, :runId, :toolId, :toolName, :inputSummary, :outputSummary,
                                :status, :authorized, :durationMillis, :errorMessage, :createdAt
                            )
                        """)
                        .param("id", toolCall.id().toString())
                        .param("tenantId", tenantId.toString())
                        .param("runId", toolCall.runId().toString())
                        .param("toolId", toolCall.toolId().toString())
                        .param("toolName", toolCall.toolName())
                        .param("inputSummary", toolCall.inputSummary())
                        .param("outputSummary", nullIfBlank(toolCall.outputSummary()))
                        .param("status", toolCall.status().name())
                        .param("authorized", toolCall.authorized())
                        .param("durationMillis", toolCall.durationMillis())
                        .param("errorMessage", nullIfBlank(toolCall.errorMessage()))
                        .param("createdAt", Timestamp.from(toolCall.createdAt()))
                        .update();
            }
        });
    }

    @Override
    public List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, run_id, tool_id, tool_name, input_summary, output_summary,
                               status, authorized, duration_ms, error_message, created_at
                        FROM tool_calls
                        WHERE tenant_id = :tenantId AND run_id = :runId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .param("runId", runId.toString())
                .query(this::mapToolCall)
                .list();
    }

    private RunToolCall mapToolCall(ResultSet resultSet, int rowNum) throws SQLException {
        long durationMillis = resultSet.getLong("duration_ms");
        Long duration = resultSet.wasNull() ? null : durationMillis;
        return new RunToolCall(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("tool_id")),
                resultSet.getString("tool_name"),
                resultSet.getString("input_summary"),
                resultSet.getString("output_summary"),
                RunStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("authorized"),
                duration,
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
