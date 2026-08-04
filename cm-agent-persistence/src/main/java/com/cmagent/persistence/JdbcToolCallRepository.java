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

/** 使用 JDBC 批量保存和查询运行过程中产生的工具调用记录。 */
public class JdbcToolCallRepository implements ToolCallRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建工具调用仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     * @param transactionTemplate 保证批量工具调用原子写入的事务模板
     */
    public JdbcToolCallRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    /**
     * 在同一事务中批量保存一次运行产生的工具调用。
     *
     * @param tenantId 当前租户标识，必须与批次及每条记录一致
     * @param toolCalls 待保存的工具调用批次
     */
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
    /**
     * 按发生顺序查询指定运行的全部工具调用。
     *
     * @param tenantId 租户标识
     * @param runId 运行标识
     * @return 工具调用列表
     */
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

    /**
     * 将当前结果集行转换为工具调用领域对象。
     *
     * @param resultSet 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return 工具调用领域对象
     * @throws SQLException 读取列值失败时抛出
     */
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

    /**
     * 将空白文本规范化为数据库 {@code NULL}。
     *
     * @param value 待规范化文本
     * @return 空白时返回 {@code null}，否则返回原值
     */
    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
