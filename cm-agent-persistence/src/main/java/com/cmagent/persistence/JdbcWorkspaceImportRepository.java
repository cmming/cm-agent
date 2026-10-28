package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.AbstractWorkspaceImportRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * 两库导入仓储；锁认证租户的固定行，使跨 Agent/实例的配额与 GC/上传竞争串行化。
 * manifest 只插入一次，后续更新不接受路径、摘要或归属替换；不在事务中执行文件 I/O。
 */
public final class JdbcWorkspaceImportRepository extends AbstractWorkspaceImportRepository {
    /** 宿主管理连接及可信命名参数。 */
    private final JdbcClient jdbc;
    /** 同一 DataSource 的短事务模板，异常向上抛出由最终边界统一脱敏记录。 */
    private final TransactionTemplate transactions;

    /** @param jdbc 宿主 JDBC 客户端 @param transactions 同一数据源的事务模板 */
    public JdbcWorkspaceImportRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
    }
    @Override protected <T> T locked(UUID tenant, Supplier<T> action) {
        return transactions.execute(status -> {
            // 未存在租户没有可见导入；插入仍由外键拒绝。不能因不存在而泄露另一租户的批次。
            jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant", tenant.toString()).query(String.class).optional();
            return action.get();
        });
    }
    @Override protected Optional<WorkspaceImport> load(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM workspace_imports WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(this::map).optional();
    }
    @Override protected List<WorkspaceImport> retained(UUID tenant) {
        return jdbc.sql("SELECT * FROM workspace_imports WHERE tenant_id=:tenant AND reclaimed=false")
                .param("tenant", tenant.toString()).query(this::map).list();
    }
    @Override protected Optional<WorkspaceImport> loadRequest(WorkspaceImportScope scope, UUID requestId) {
        return jdbc.sql("SELECT * FROM workspace_imports WHERE tenant_id=:tenant AND principal_id=:owner AND agent_id=:agent AND request_id=:request")
                .param("tenant", scope.tenantId().toString()).param("owner", scope.principalId()).param("agent", scope.agentId().toString())
                .param("request", requestId.toString()).query(this::map).optional().filter(v -> v.scope().equals(scope));
    }
    @Override protected void persist(WorkspaceImport value, boolean inserting) {
        if (inserting) {
            jdbc.sql("""
                    INSERT INTO workspace_imports (id,tenant_id,principal_id,agent_id,display_name,manifest_digest,
                      state,version,total_bytes,created_at,expires_at,reclaimed,request_id)
                    VALUES (:id,:tenant,:owner,:agent,:name,:digest,:state,:version,:bytes,:created,:expires,:reclaimed,:request)
                    """).param("id", value.id().toString()).param("tenant", value.scope().tenantId().toString())
                    .param("owner", value.scope().principalId()).param("agent", value.scope().agentId().toString())
                    .param("name", value.displayName()).param("digest", value.manifestDigest()).param("state", value.state().name())
                    .param("version", value.version()).param("bytes", value.totalBytes()).param("created", Timestamp.from(value.createdAt()))
                    .param("expires", Timestamp.from(value.expiresAt())).param("reclaimed", value.reclaimed())
                    .param("request", value.requestId() == null ? null : value.requestId().toString(), Types.VARCHAR).update();
            int ordinal = 0;
            for (var file : value.files()) jdbc.sql("""
                    INSERT INTO workspace_import_files (tenant_id,import_id,file_id,ordinal,relative_path,size_bytes,sha256,uploaded)
                    VALUES (:tenant,:id,:file,:ordinal,:path,:bytes,:hash,false)
                    """).param("tenant", value.scope().tenantId().toString()).param("id", value.id().toString())
                    .param("file", file.id().toString()).param("ordinal", ordinal++).param("path", file.relativePath())
                    .param("bytes", file.sizeBytes()).param("hash", file.sha256()).update();
        } else {
            // 更新仍包含 owner/Agent 与版本，避免未来调用者绕过可信边界或覆盖并发修改。
            int updated = jdbc.sql("""
                    UPDATE workspace_imports SET state=:state,version=:version,expires_at=:expires,reclaimed=:reclaimed
                    WHERE tenant_id=:tenant AND id=:id AND principal_id=:owner AND agent_id=:agent AND version=:previous
                    """).param("state", value.state().name()).param("version", value.version()).param("expires", Timestamp.from(value.expiresAt()))
                    .param("reclaimed", value.reclaimed()).param("tenant", value.scope().tenantId().toString()).param("id", value.id().toString())
                    .param("owner", value.scope().principalId()).param("agent", value.scope().agentId().toString()).param("previous", value.version() - 1).update();
            if (updated != 1) throw new IllegalStateException("导入版本未能原子更新");
            for (UUID file : value.uploaded()) jdbc.sql("UPDATE workspace_import_files SET uploaded=true WHERE tenant_id=:tenant AND import_id=:id AND file_id=:file")
                    .param("tenant", value.scope().tenantId().toString()).param("id", value.id().toString()).param("file", file.toString()).update();
        }
    }
    @Override protected List<WorkspaceImportLease> leases(UUID tenant, UUID id) {
        return jdbc.sql("SELECT token,kind,lease_until FROM workspace_import_leases WHERE tenant_id=:tenant AND import_id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query((r, n) -> new WorkspaceImportLease(
                        UUID.fromString(r.getString("token")), WorkspaceImportLease.Kind.valueOf(r.getString("kind")), r.getTimestamp("lease_until").toInstant())).list();
    }
    @Override protected void putLease(UUID tenant, UUID id, WorkspaceImportLease lease) {
        removeLease(tenant, id, lease.token());
        jdbc.sql("INSERT INTO workspace_import_leases (tenant_id,import_id,token,kind,lease_until) VALUES (:tenant,:id,:token,:kind,:until)")
                .param("tenant", tenant.toString()).param("id", id.toString()).param("token", lease.token().toString())
                .param("kind", lease.kind().name()).param("until", Timestamp.from(lease.until())).update();
    }
    @Override protected void removeLease(UUID tenant, UUID id, UUID token) {
        jdbc.sql("DELETE FROM workspace_import_leases WHERE tenant_id=:tenant AND import_id=:id AND token=:token")
                .param("tenant", tenant.toString()).param("id", id.toString()).param("token", token.toString()).update();
    }
    @Override public List<WorkspaceImport> cleanupCandidates(Instant now) {
        // 唯一跨租户查询只服务维护扫描；返回后必须以候选可信 scope 获取 GC 租约再删除。
        return jdbc.sql("""
                SELECT * FROM workspace_imports WHERE reclaimed=false AND (state IN ('REVOKED','EXPIRED') OR expires_at<=:now)
                ORDER BY expires_at,id LIMIT 100
                """).param("now", Timestamp.from(now)).query(this::map).list();
    }
    private WorkspaceImport map(ResultSet row, int index) throws SQLException {
        UUID tenant = UUID.fromString(row.getString("tenant_id")), id = UUID.fromString(row.getString("id"));
        var uploaded = new HashSet<UUID>();
        var files = jdbc.sql("SELECT * FROM workspace_import_files WHERE tenant_id=:tenant AND import_id=:id ORDER BY ordinal")
                .param("tenant", tenant.toString()).param("id", id.toString()).query((r, n) -> {
                    UUID file = UUID.fromString(r.getString("file_id")); if (r.getBoolean("uploaded")) uploaded.add(file);
                    return new WorkspaceImportFile(file, r.getString("relative_path"), r.getInt("size_bytes"), r.getString("sha256"));
                }).list();
        return new WorkspaceImport(id, new WorkspaceImportScope(tenant, row.getString("principal_id"), UUID.fromString(row.getString("agent_id"))),
                row.getString("display_name"), files, row.getString("manifest_digest"), WorkspaceImport.State.valueOf(row.getString("state")),
                uploaded, row.getLong("version"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(), row.getBoolean("reclaimed"),
                row.getString("request_id") == null ? null : UUID.fromString(row.getString("request_id")));
    }
}
