package com.cmagent.persistence;

import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunKind;
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
    @Override public boolean supportsWorkspaceProtocol(){return true;}
    /** 固定Run来源和关联会话任一使用导入，都在排序/LIMIT前复核原主体，防止经独立Run列表旁路。 */
    @Override public List<RunRecord> listVisible(UUID tenantId, UUID agentId, String principalId, RunPageRequest page) {
        String runOwner = JdbcOwnerPredicate.equal(jdbcClient, "r.principal_id", ":principal");
        String fixedOwner = JdbcOwnerPredicate.equal(jdbcClient, "s.principal_id", ":principal");
        String bindingOwner = JdbcOwnerPredicate.equal(jdbcClient, "b.principal_id", ":principal");
        String creator = JdbcOwnerPredicate.equal(jdbcClient, "c.created_by", ":principal");
        var query = jdbcClient.sql("""
                SELECT r.* FROM runs r WHERE r.tenant_id=:tenant AND r.agent_id=:agent AND r.run_kind='NORMAL'
                AND (r.workspace_protocol=0 OR (
                """+runOwner+"))"+"""
                AND NOT EXISTS (SELECT 1 FROM run_workspace_selections s WHERE s.tenant_id=r.tenant_id AND s.agent_id=r.agent_id
                  AND s.resource_id=r.id AND s.selection_mode='IMPORTED' AND NOT (
                """ + fixedOwner + " AND " + runOwner + "))"
                + " AND NOT EXISTS (SELECT 1 FROM messages m JOIN conversations c ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id"
                + " JOIN conversation_workspace_bindings b ON b.tenant_id=c.tenant_id AND b.agent_id=c.agent_id AND b.resource_id=c.id"
                + " WHERE m.tenant_id=r.tenant_id AND m.run_id=r.id AND b.selection_mode='IMPORTED' AND NOT ("
                + bindingOwner + " AND " + creator + " AND " + runOwner + "))"
                + " AND NOT EXISTS (SELECT 1 FROM messages hm JOIN conversations hc ON hc.tenant_id=hm.tenant_id AND hc.id=hm.conversation_id"
                + " WHERE hm.tenant_id=r.tenant_id AND hm.run_id=r.id AND hc.workspace_protocol=3 AND NOT ("
                + JdbcOwnerPredicate.equal(jdbcClient,"hc.created_by",":principal")+" AND "+runOwner+"))"
                + " AND NOT EXISTS (SELECT 1 FROM messages cm JOIN conversations cc ON cc.tenant_id=cm.tenant_id AND cc.id=cm.conversation_id"
                + " JOIN messages pm ON pm.tenant_id=cc.tenant_id AND pm.conversation_id=cc.id"
                + " JOIN runs pr ON pr.tenant_id=pm.tenant_id AND pr.id=pm.run_id"
                + " WHERE cm.tenant_id=r.tenant_id AND cm.run_id=r.id AND pr.workspace_protocol=3 AND NOT ("
                + JdbcOwnerPredicate.equal(jdbcClient,"pr.principal_id",":principal")+" AND "
                + JdbcOwnerPredicate.equal(jdbcClient,"cc.created_by",":principal")+" AND "+runOwner+"))"
                + (page.beforeStartedAt() == null ? "" : " AND (r.started_at<:before OR (r.started_at=:before AND r.id<:beforeId))")
                + " ORDER BY r.started_at DESC,r.id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("agent", agentId.toString()).param("principal", principalId).param("limit", page.limit());
        if (page.beforeStartedAt() != null) query = query.param("before", Timestamp.from(page.beforeStartedAt())).param("beforeId", page.beforeId().toString());
        return query.query(this::mapRun).list();
    }
    @Override
    public boolean resumeWaitingApproval(RunRecord snapshot) {
        if (snapshot.status() != RunStatus.WAITING_APPROVAL) return false;
        // 审批决定和状态恢复共享上层短事务，归属条件不能由客户端提交的主体替代。
        return jdbcClient.sql("""
                UPDATE runs SET status = 'RUNNING'
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND id = :runId
                  AND principal_id = :principalId AND status = 'WAITING_APPROVAL'
                """).param("tenantId", snapshot.tenantId().toString()).param("agentId", snapshot.agentId().toString())
                .param("runId", snapshot.id().toString()).param("principalId", snapshot.principalId()).update() == 1;
    }
    @Override
    public boolean requestCancellation(RunRecord snapshot) {
        if (snapshot.status() != RunStatus.RUNNING && snapshot.status() != RunStatus.WAITING_APPROVAL) return false;
        // 使用仓储快照完整归属和原状态竞争，完成先提交时不能覆盖其结果。
        return jdbcClient.sql("""
                UPDATE runs SET status = 'CANCELLING'
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND id = :runId
                  AND principal_id = :principalId AND status = :status
                """)
                .param("tenantId", snapshot.tenantId().toString()).param("agentId", snapshot.agentId().toString())
                .param("runId", snapshot.id().toString()).param("principalId", snapshot.principalId())
                .param("status", snapshot.status().name()).update() == 1;
    }
    @Override
    public boolean expireWaitingApproval(RunRecord snapshot, Instant finishedAt) {
        // 必须只命中等待态，避免读写期间恢复为 RUNNING 后仍被扫描终结。
        return jdbcClient.sql("""
                UPDATE runs SET status = 'DENIED', output_text = NULL,
                    error_message = '工具审批已过期', finished_at = :finishedAt
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND id = :runId
                  AND principal_id = :principalId AND status = 'WAITING_APPROVAL'
                """).param("finishedAt", Timestamp.from(finishedAt))
                .param("tenantId", snapshot.tenantId().toString()).param("agentId", snapshot.agentId().toString())
                .param("runId", snapshot.id().toString()).param("principalId", snapshot.principalId()).update() == 1;
    }
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
        return saveWithWorkspaceProtocol(tenantId, run, 0);
    }
    /** 首次INSERT包含不可变见证，避免先暴露运行输入再补选择元数据的跨实例窗口。 */
    @Override public RunRecord saveWithWorkspaceProtocol(UUID tenantId, RunRecord run, int protocol) {
        if (protocol != 0 && protocol != 3) throw new IllegalArgumentException("工作区运行协议不合法");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(run, "run 不能为空");
        if (!tenantId.equals(run.tenantId())) {
            throw new IllegalArgumentException("tenantId 与 run.tenantId 不匹配");
        }

        jdbcClient.sql("""
                        INSERT INTO runs (
                            id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
                            error_message, started_at, finished_at, workspace_protocol
                        ) VALUES (
                            :id, :tenantId, :agentId, :principalId, :runKind, :status, :input, :output,
                            :errorMessage, :startedAt, :finishedAt, :workspaceProtocol
                        )
                        """)
                .param("id", run.id().toString())
                .param("tenantId", tenantId.toString())
                .param("agentId", run.agentId().toString())
                .param("principalId", run.principalId())
                .param("runKind", run.kind().name())
                .param("status", run.status().name())
                .param("input", run.input())
                .param("output", nullIfBlank(run.output()))
                .param("errorMessage", nullIfBlank(run.errorMessage()))
                .param("startedAt", Timestamp.from(run.startedAt()))
                .param("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
                .param("workspaceProtocol", protocol)
                .update();
        return run;
    }
    /** 来源见证独立于选择/检查点；丢失Run不能伪装成旧记录。 */
    @Override public int workspaceProtocol(UUID tenant, UUID agent, UUID run) {
        return jdbcClient.sql("SELECT workspace_protocol FROM runs WHERE tenant_id=:tenant AND agent_id=:agent AND id=:run")
                .param("tenant",tenant.toString()).param("agent",agent.toString()).param("run",run.toString()).query(Integer.class).optional()
                .orElseThrow(()->new java.util.NoSuchElementException("运行不存在"));
    }

    /** 选择行丢失也不能公开已派生的标题或回答，依据长期Run见证与原创建者判定。 */
    @Override public boolean visibleConversationFromWorkspace(com.cmagent.core.domain.Conversation conversation,String principal) {
        String owner=JdbcOwnerPredicate.equal(jdbcClient,"r.principal_id",":principal");
        if(!conversation.createdBy().equals(principal)) owner="1=0";
        return jdbcClient.sql("SELECT COUNT(*) FROM messages m JOIN runs r ON r.tenant_id=m.tenant_id AND r.id=m.run_id"
                +" WHERE m.tenant_id=:tenant AND m.conversation_id=:conversation AND r.workspace_protocol=3 AND NOT ("+owner+")")
                .param("tenant",conversation.tenantId().toString()).param("conversation",conversation.id().toString())
                .param("principal",principal).query(Long.class).single()==0;
    }

    @Override
    public RunRecord waitForApproval(UUID tenantId, UUID runId) {
        RunRecord existing = findByTenantAndId(tenantId, runId)
                .orElseThrow(() -> new NoSuchElementException("Run 不存在"));
        RunRecord waiting = existing.waitForApproval();
        int updated = jdbcClient.sql("""
                        UPDATE runs SET status = 'WAITING_APPROVAL'
                        WHERE tenant_id = :tenantId AND id = :runId AND status = 'RUNNING'
                        """)
                .param("tenantId", tenantId.toString()).param("runId", runId.toString()).update();
        if (updated != 1) {
            throw new NoSuchElementException("Run 不存在或状态已变化");
        }
        return waiting;
    }

    @Override
    /**
     * 按读取到的活动状态进行条件终态更新；停止中的运行不能被迟到成功覆盖。
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
        // MySQL 8.4 的现有 TIMESTAMP(0) 列会把亚秒开始时间舍入到下一秒；快速运行的完成时间
        // 可能早于回读的开始时间。以持久化开始时间作为下界，保持领域不变量和双库一致语义。
        Instant persistedFinishedAt = finishedAt.isBefore(existing.startedAt()) ? existing.startedAt() : finishedAt;
        RunRecord completed = existing.complete(status, output, errorMessage, persistedFinishedAt);

        int updated = jdbcClient.sql("""
                        UPDATE runs
                        SET status = :status,
                            output_text = :output,
                            error_message = :errorMessage,
                            finished_at = :finishedAt
                        WHERE tenant_id = :tenantId AND id = :runId
                          AND status = :expectedStatus
                        """)
                .param("status", completed.status().name())
                .param("output", nullIfBlank(completed.output()))
                .param("errorMessage", nullIfBlank(completed.errorMessage()))
                .param("finishedAt", Timestamp.from(completed.finishedAt()))
                .param("tenantId", tenantId.toString())
                .param("runId", runId.toString())
                .param("expectedStatus", existing.status().name())
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
                        SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
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
                            SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
                                   error_message, started_at, finished_at
                            FROM runs
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                              AND run_kind = 'NORMAL'
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
                        SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
                               error_message, started_at, finished_at
                        FROM runs
                        WHERE tenant_id = :tenantId
                          AND agent_id = :agentId
                          AND run_kind = 'NORMAL'
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
    @Override
    public List<RunRecord> listByTenantAndAgentAndKind(
            UUID tenantId, UUID agentId, RunKind kind, RunPageRequest pageRequest) {
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        if (pageRequest.beforeStartedAt() == null) {
            return jdbcClient.sql("""
                            SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
                                   error_message, started_at, finished_at
                            FROM runs
                            WHERE tenant_id = :tenantId AND agent_id = :agentId AND run_kind = :runKind
                            ORDER BY started_at DESC, id DESC
                            LIMIT :limit
                            """)
                    .param("tenantId", tenantId.toString())
                    .param("agentId", agentId.toString())
                    .param("runKind", kind.name())
                    .param("limit", pageRequest.limit())
                    .query(this::mapRun)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
                               error_message, started_at, finished_at
                        FROM runs
                        WHERE tenant_id = :tenantId
                          AND agent_id = :agentId
                          AND run_kind = :runKind
                          AND (started_at < :beforeStartedAt
                               OR (started_at = :beforeStartedAt AND id < :beforeId))
                        ORDER BY started_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("runKind", kind.name())
                .param("beforeStartedAt", Timestamp.from(pageRequest.beforeStartedAt()))
                .param("beforeId", pageRequest.beforeId().toString())
                .param("limit", pageRequest.limit())
                .query(this::mapRun)
                .list();
    }

    private Optional<RunRecord> findByTenantAndId(UUID tenantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, principal_id, run_kind, status, input_text, output_text,
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
                RunKind.valueOf(resultSet.getString("run_kind")),
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
