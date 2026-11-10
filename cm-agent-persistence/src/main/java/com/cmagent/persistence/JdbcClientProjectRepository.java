package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.core.repository.ClientProjectRepository;
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
 * 客户端项目元数据仓储，不保存目录明文且不执行客户端文件系统操作。
 *
 * <p>列表的 tenant/owner/state 过滤必须先于 LIMIT；设备与根摘要使用普通等值比较，
 * 不能依赖 MySQL 的大小写不敏感排序规则替代身份校验。</p>
 */
public final class JdbcClientProjectRepository implements ClientProjectRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String owner;

    public JdbcClientProjectRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override public Optional<ClientProject> find(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT * FROM client_projects WHERE tenant_id=:tenant AND id=:id AND " + owner)
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("id", projectId.toString()).query(this::map).optional();
    }

    /** 行锁只在调用方短事务内有效，不跨设备任务或文件 I/O 持有。 */
    @Override public Optional<ClientProject> lock(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT * FROM client_projects WHERE tenant_id=:tenant AND id=:id AND "
                + owner + " FOR UPDATE").param("tenant", tenantId.toString())
                .param("owner", ownerId).param("id", projectId.toString())
                .query(this::map).optional();
    }

    @Override public List<ClientProject> list(UUID tenantId, String ownerId,
            LocalProjectPageRequest page, boolean includeArchived) {
        var query = jdbc.sql("SELECT * FROM client_projects WHERE tenant_id=:tenant AND " + owner
                + (includeArchived ? "" : " AND state='ACTIVE'")
                + (page.beforeUpdatedAt() == null ? "" : " AND (updated_at<:before OR (updated_at=:before AND id<:beforeId))")
                + " ORDER BY updated_at DESC,id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("limit", page.limit());
        if (page.beforeUpdatedAt() != null) {
            query = query.param("before", Timestamp.from(page.beforeUpdatedAt()))
                    .param("beforeId", page.beforeId().toString());
        }
        return query.query(this::map).list();
    }

    @Override public Optional<ClientProject> findByRoot(UUID tenantId, String ownerId,
            UUID deviceId, String rootIdentity) {
        return jdbc.sql("SELECT * FROM client_projects WHERE tenant_id=:tenant AND device_id=:device"
                + " AND root_identity=:identity AND " + owner)
                .param("tenant", tenantId.toString()).param("device", deviceId.toString())
                .param("identity", rootIdentity).param("owner", ownerId).query(this::map).optional();
    }

    @Override public boolean insert(ClientProject project) {
        if (project.version() != 1 || project.state() != ClientProject.State.ACTIVE) {
            throw new IllegalArgumentException("客户端项目只能以初始活动版本新增");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.sql("""
                INSERT INTO client_projects(id,tenant_id,owner_id,device_id,client_root_id,
                  root_identity,display_name,state,version,created_at,updated_at)
                VALUES (:id,:tenant,:owner,:device,:root,:identity,:name,'ACTIVE',1,:created,:updated)
                """).param("id", project.id().toString()).param("tenant", project.tenantId().toString())
                .param("owner", project.ownerId()).param("device", project.deviceId().toString())
                .param("root", project.clientRootId().toString()).param("identity", project.rootIdentity())
                .param("name", project.name()).param("created", Timestamp.from(project.createdAt()))
                .param("updated", Timestamp.from(project.updatedAt())).update() == 1));
    }

    @Override public boolean rename(UUID tenantId, String ownerId, UUID projectId,
            long expectedVersion, String name, Instant now) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("项目展示名不合法");
        }
        return mutation("display_name=:name,", tenantId, ownerId, projectId, expectedVersion, now)
                .param("name", name).update() == 1;
    }

    @Override public boolean archive(UUID tenantId, String ownerId, UUID projectId,
            long expectedVersion, Instant now) {
        return mutation("state='ARCHIVED',", tenantId, ownerId, projectId, expectedVersion, now)
                .update() == 1;
    }

    private JdbcClient.StatementSpec mutation(String set, UUID tenantId, String ownerId,
            UUID projectId, long expectedVersion, Instant now) {
        if (expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("项目条件版本不合法");
        }
        return jdbc.sql("UPDATE client_projects SET " + set + "version=version+1,updated_at=:now"
                + " WHERE tenant_id=:tenant AND id=:id AND " + owner
                + " AND state='ACTIVE' AND version=:version AND updated_at<=:now")
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("id", projectId.toString()).param("version", expectedVersion)
                .param("now", Timestamp.from(now));
    }

    private ClientProject map(ResultSet row, int index) throws SQLException {
        return new ClientProject(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("device_id")), UUID.fromString(row.getString("client_root_id")),
                row.getString("root_identity"), row.getString("display_name"),
                ClientProject.State.valueOf(row.getString("state")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
