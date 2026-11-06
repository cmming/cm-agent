package com.cmagent.persistence;

import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.core.domain.LocalProjectRoot;
import com.cmagent.core.repository.LocalProjectRepository;
import com.cmagent.core.repository.LocalProjectRootRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 双库通用的私有项目及加密根仓储；不提供更换根、读取明文路径或删除用户目录的操作。
 *
 * <p>owner使用固定SQL的逐字比较，不能让MySQL默认大小写/尾空格规则合并不同主体。
 * 根和项目创建由上层共享短事务并写严格审计，仓储本身不接触本机文件系统或持有密钥。</p>
 */
public final class JdbcLocalProjectRepository implements LocalProjectRepository, LocalProjectRootRepository {
    /** 命名参数JDBC入口，不依赖Web主体或Controller。 */
    private final JdbcClient jdbc;
    /** 只在固定owner列中使用的双方言精确谓词，不拼接客户端列名。 */
    private final String owner;
    /** 新增按真实租户行串行化，避免PostgreSQL重复键异常使上层共享审计事务进入失败态。 */
    private final TransactionTemplate transaction;

    /**
     * @param jdbc 由可信DataSource创建的命名参数客户端，不能为null
     * @param transaction 必须与jdbc使用同一DataSource/事务管理器；由宿主持有并关闭数据源
     */
    public JdbcLocalProjectRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override public boolean insertRoot(LocalProjectRoot root) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(root.tenantId()) || exists("local_project_roots", root.tenantId(), root.id())) return false;
            return jdbc.sql("""
                    INSERT INTO local_project_roots(id,tenant_id,owner_id,node_id,encrypted_directory,identity_digest,created_at)
                    VALUES (:id,:tenant,:owner,:node,:encrypted,:identity,:created)
                    """).param("id", root.id().toString()).param("tenant", root.tenantId().toString())
                    .param("owner", root.ownerId()).param("node", root.nodeId()).param("encrypted", root.encryptedDirectory())
                    .param("identity", root.identity()).param("created", Timestamp.from(root.createdAt())).update() == 1;
        }));
    }

    @Override public Optional<LocalProjectRoot> root(UUID tenantId, String ownerId, UUID rootId) {
        return jdbc.sql("SELECT * FROM local_project_roots WHERE tenant_id=:tenant AND id=:id AND " + owner)
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("id", rootId.toString())
                .query((row, index) -> new LocalProjectRoot(UUID.fromString(row.getString("id")),
                        UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"), row.getString("node_id"),
                        row.getString("encrypted_directory"), row.getString("identity_digest"), row.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override public Optional<LocalProject> find(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT * FROM local_projects WHERE tenant_id=:tenant AND id=:id AND " + owner)
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("id", projectId.toString())
                .query(this::map).optional();
    }

    /** 行锁由外层短事务持有，不能把返回值用于解除锁后的文件副作用授权。 */
    @Override public Optional<LocalProject> lock(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT * FROM local_projects WHERE tenant_id=:tenant AND id=:id AND " + owner + " FOR UPDATE")
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("id", projectId.toString()).query(this::map).optional();
    }

    @Override public List<LocalProject> list(UUID tenantId, String ownerId, LocalProjectPageRequest page, boolean includeArchived) {
        // 私有主体/归档过滤必须在LIMIT之前；不能先截取其他主体的行再在Java过滤。
        var sql = jdbc.sql("SELECT * FROM local_projects WHERE tenant_id=:tenant AND " + owner
                + (includeArchived ? "" : " AND state='ACTIVE'")
                + (page.beforeUpdatedAt() == null ? "" : " AND (updated_at<:before OR (updated_at=:before AND id<:beforeId))")
                + " ORDER BY updated_at DESC,id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("limit", page.limit());
        if (page.beforeUpdatedAt() != null)
            sql = sql.param("before", Timestamp.from(page.beforeUpdatedAt())).param("beforeId", page.beforeId().toString());
        return sql.query(this::map).list();
    }

    @Override public boolean insert(LocalProject project) {
        if (project.version() != 1 || project.state() != LocalProject.State.ACTIVE)
            throw new IllegalArgumentException("项目只能以初始活动版本新增");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockTenant(project.tenantId()) || exists("local_projects", project.tenantId(), project.id())) return false;
            var root = root(project.tenantId(), project.ownerId(), project.rootBindingId()).orElse(null);
            if (root == null || !root.nodeId().equals(project.nodeId()) || !root.identity().equals(project.rootIdentity())) return false;
            return jdbc.sql("""
                    INSERT INTO local_projects(id,tenant_id,owner_id,display_name,node_id,root_binding_id,
                      root_identity,state,version,created_at,updated_at)
                    VALUES (:id,:tenant,:owner,:name,:node,:root,:identity,'ACTIVE',1,:created,:updated)
                    """).param("id", project.id().toString()).param("tenant", project.tenantId().toString())
                    .param("owner", project.ownerId()).param("name", project.name()).param("node", project.nodeId())
                    .param("root", project.rootBindingId().toString()).param("identity", project.rootIdentity())
                    .param("created", Timestamp.from(project.createdAt())).param("updated", Timestamp.from(project.updatedAt())).update() == 1;
        }));
    }

    private boolean lockTenant(UUID tenant) {
        return jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant", tenant.toString())
                .query(String.class).optional().isPresent();
    }

    /** 表名仅来自本类常量；检查重复不返回其他主体的元数据。 */
    private boolean exists(String table, UUID tenant, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(Long.class).single() != 0;
    }

    @Override public boolean rename(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, String name, Instant now) {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("项目展示名不合法");
        return mutation("display_name=:name,", tenantId, ownerId, projectId, expectedVersion, now).param("name", name).update() == 1;
    }

    @Override public boolean archive(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, Instant now) {
        return mutation("state='ARCHIVED',", tenantId, ownerId, projectId, expectedVersion, now).update() == 1;
    }

    /** 固定SET片段只来自本类，根/节点/owner不允许经通用更新被改写。 */
    private JdbcClient.StatementSpec mutation(String set, UUID tenant, String principal, UUID id, long version, Instant now) {
        if (version < 1 || version == Long.MAX_VALUE) throw new IllegalArgumentException("项目条件版本不合法");
        return jdbc.sql("UPDATE local_projects SET " + set + "version=version+1,updated_at=:now "
                + "WHERE tenant_id=:tenant AND id=:id AND " + owner + " AND state='ACTIVE' AND version=:version AND updated_at<=:now")
                .param("tenant", tenant.toString()).param("owner", principal).param("id", id.toString())
                .param("version", version).param("now", Timestamp.from(now));
    }

    private LocalProject map(ResultSet row, int index) throws SQLException {
        return new LocalProject(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                row.getString("owner_id"), row.getString("display_name"), row.getString("node_id"),
                UUID.fromString(row.getString("root_binding_id")), row.getString("root_identity"),
                LocalProject.State.valueOf(row.getString("state")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}

