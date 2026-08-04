package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditPageRequest;
import com.cmagent.core.audit.AuditEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 JDBC 追加和分页读取不可变审计事件。 */
public class JdbcAuditEventRepository implements AuditEventRepository {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建审计事件仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     * @param transactionTemplate 保证批量审计事件原子写入的事务模板
     */
    public JdbcAuditEventRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    /**
     * 追加单条审计事件；已存在相同 ID 时不覆盖历史内容。
     *
     * @param event 待追加的审计事件
     */
    public void append(AuditEvent event) {
        jdbcClient.sql("""
                        INSERT INTO audit_events (
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            :principalId,
                            :eventType,
                            :resourceType,
                            :resourceId,
                            :status,
                            :message,
                            :createdAt
                        )
                        """)
                .param("id", event.id().toString())
                .param("tenantId", event.tenantId().toString())
                .param("principalId", event.principalId())
                .param("eventType", event.eventType())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("status", event.status())
                .param("message", event.message())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }

    @Override
    /**
     * 在同一事务中按输入顺序追加多条审计事件。
     *
     * @param events 待追加的审计事件列表
     */
    public void appendAll(List<AuditEvent> events) {
        List<AuditEvent> batch = List.copyOf(Objects.requireNonNull(events, "events 不能为空"));
        transactionTemplate.executeWithoutResult(status -> batch.forEach(this::append));
    }

    @Override
    /**
     * 查询租户最新的指定数量审计事件。
     *
     * @param tenantId 租户标识
     * @param limit 最大返回数量
     * @return 按创建时间倒序排列的审计事件
     */
    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        return query(tenantId, limit, null);
    }

    @Override
    /**
     * 声明 JDBC 实现支持稳定的复合游标分页。
     *
     * @return 始终返回 {@code true}
     */
    public boolean supportsCursorPagination() {
        return true;
    }

    @Override
    /**
     * 使用“创建时间 + ID”复合游标查询租户审计事件。
     *
     * @param tenantId 租户标识
     * @param pageRequest 游标位置和本页容量
     * @return 当前页审计事件
     */
    public List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        return query(tenantId, pageRequest.limit(), pageRequest);
    }

    /**
     * 根据是否携带游标选择首屏或后续页 SQL，并始终附加租户条件。
     *
     * @param tenantId 租户标识
     * @param limit 最大返回数量
     * @param pageRequest 可选的复合游标请求
     * @return 查询得到的审计事件
     */
    private List<AuditEvent> query(UUID tenantId, int limit, AuditPageRequest pageRequest) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        String sql = pageRequest == null || pageRequest.beforeCreatedAt() == null
                ? """
                        SELECT
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        FROM audit_events
                        WHERE tenant_id = :tenantId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """
                : """
                        SELECT
                            id,
                            tenant_id,
                            principal_id,
                            event_type,
                            resource_type,
                            resource_id,
                            status,
                            message,
                            created_at
                        FROM audit_events
                        WHERE tenant_id = :tenantId
                          AND (created_at < :beforeCreatedAt
                               OR (created_at = :beforeCreatedAt AND id < :beforeId))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """;
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("tenantId", tenantId.toString())
                .param("limit", limit);
        if (pageRequest != null && pageRequest.beforeCreatedAt() != null) {
            statement = statement
                    .param("beforeCreatedAt", Timestamp.from(pageRequest.beforeCreatedAt()))
                    .param("beforeId", pageRequest.beforeId().toString());
        }
        return statement.query((rs, rowNum) -> new AuditEvent(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("principal_id"),
                rs.getString("event_type"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant()
        )).list();
    }
}
