package com.cmagent.persistence;

import com.cmagent.core.domain.WorkspaceBinding;
import com.cmagent.core.domain.WorkspaceSelection;
import com.cmagent.core.repository.WorkspaceSelectionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.sql.*;
import java.util.*;

/**
 * 两库通用的选择元数据仓储；表名固定，所有查询与更新均包含可信租户和 Agent。
 * 会话首发及改绑需由调用方在持有 conversations 行锁的短事务中执行。
 */
public final class JdbcWorkspaceSelectionRepository implements WorkspaceSelectionRepository {
    /** 由宿主创建并管理连接/事务，仓储不持有独立连接。 */
    private final JdbcClient jdbc;
    /** @param jdbc 已加入宿主事务的数据访问客户端 */
    public JdbcWorkspaceSelectionRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<WorkspaceBinding> conversation(UUID tenant, UUID agent, UUID id) {
        return find("conversation_workspace_bindings", tenant, agent, id);
    }
    @Override public Optional<WorkspaceBinding> run(UUID tenant, UUID agent, UUID id) {
        return find("run_workspace_selections", tenant, agent, id);
    }
    private Optional<WorkspaceBinding> find(String table, UUID tenant, UUID agent, UUID id) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE tenant_id=:tenant AND agent_id=:agent AND resource_id=:resource")
                .param("tenant", tenant.toString()).param("agent", agent.toString()).param("resource", id.toString())
                .query(this::map).optional();
    }
    @Override public boolean saveConversation(WorkspaceBinding binding, long expectedVersion) {
        if (binding.version() != expectedVersion + 1) return false;
        if (expectedVersion == 0) {
            // 调用方持有会话行锁，首次插入不使用数据库特有的 UPSERT，避免覆盖并发选择。
            if (conversation(binding.tenantId(), binding.agentId(), binding.resourceId()).isPresent()) return false;
            insert("conversation_workspace_bindings", binding); return true;
        }
        return parameters(jdbc.sql("""
                UPDATE conversation_workspace_bindings SET selection_mode=:mode,workspace_id=:workspace,
                mapping_digest=:digest,display_name=:name,directory=:directory,version=:version,
                principal_id=:principal,updated_at=:updated
                WHERE tenant_id=:tenant AND agent_id=:agent AND resource_id=:resource AND version=:expected
                """), binding).param("expected", expectedVersion).update() == 1;
    }
    @Override public void abandonConversation(WorkspaceBinding binding) {
        jdbc.sql("DELETE FROM conversation_workspace_bindings WHERE tenant_id=:tenant AND agent_id=:agent AND resource_id=:resource AND version=1")
                .param("tenant",binding.tenantId().toString()).param("agent",binding.agentId().toString())
                .param("resource",binding.resourceId().toString()).update();
    }
    @Override public void saveRun(WorkspaceBinding binding) { insert("run_workspace_selections", binding); }
    private void insert(String table, WorkspaceBinding binding) {
        parameters(jdbc.sql("INSERT INTO " + table + """
                 (tenant_id,agent_id,resource_id,selection_mode,workspace_id,mapping_digest,display_name,directory,
                 version,principal_id,created_at,updated_at)
                 VALUES (:tenant,:agent,:resource,:mode,:workspace,:digest,:name,:directory,:version,:principal,:created,:updated)
                """), binding).update();
    }
    private JdbcClient.StatementSpec parameters(JdbcClient.StatementSpec sql, WorkspaceBinding b) {
        return sql.param("tenant",b.tenantId().toString()).param("agent",b.agentId().toString())
                .param("resource",b.resourceId().toString()).param("mode",b.selection().mode().name())
                .param("workspace",b.selection().workspaceId() == null ? null : b.selection().workspaceId().toString(), Types.VARCHAR)
                .param("digest",b.mappingDigest()).param("name",b.displayName()).param("directory",b.directory())
                .param("version",b.version()).param("principal",b.principalId())
                .param("created",Timestamp.from(b.createdAt())).param("updated",Timestamp.from(b.updatedAt()));
    }
    private WorkspaceBinding map(ResultSet row, int index) throws SQLException {
        String id = row.getString("workspace_id");
        return new WorkspaceBinding(UUID.fromString(row.getString("tenant_id")),UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("resource_id")),new WorkspaceSelection(WorkspaceSelection.Mode.valueOf(row.getString("selection_mode")),
                id == null ? null : UUID.fromString(id)),row.getString("mapping_digest"),row.getString("display_name"),
                row.getString("directory"),row.getLong("version"),row.getString("principal_id"),
                row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());
    }
}
