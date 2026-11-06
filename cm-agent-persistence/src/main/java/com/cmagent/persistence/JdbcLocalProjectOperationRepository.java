package com.cmagent.persistence;

import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久操作意图与条件推进，不执行副作用；UNKNOWN不能回到可派发状态。 */
public final class JdbcLocalProjectOperationRepository implements LocalProjectOperationRepository {
    /** 固定数据源入口，载荷只存受保护密文。 */
    private final JdbcClient jdbc;
    /** 与严格审计共享的短事务，状态核验与推进使用行锁。 */
    private final TransactionTemplate transaction;
    /** 固定owner列的双方言逐字匹配谓词。 */
    private final String owner;
    /** 系统发现仍须逐字核对操作/项目owner，不能让MySQL默认排序规则扩大匹配范围。 */
    private final String retentionOwnerJoin;
    /** 节点配置逐字匹配，不能把大小写不同的其他执行节点当成本机。 */
    private final String retentionNode;

    /**
     * @param jdbc 可信数据源的命名参数入口，不能为null
     * @param transaction 与jdbc共用数据源的短事务；本机副作用必须由服务在事务外执行
     */
    public JdbcLocalProjectOperationRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
        this.retentionOwnerJoin=JdbcOwnerPredicate.equal(jdbc,"o.owner_id","p.owner_id");
        this.retentionNode=JdbcOwnerPredicate.equal(jdbc,"p.node_id",":node");
    }

    /** 跨租户能力仅供受控系统GC；项目归属/节点条件先于分页，不要求项目仍ACTIVE以免归档泄留正文。 */
    @Override public List<LocalProjectOperation> findCommandRetentionCandidates(String nodeId,Instant cutoff,CommandRetentionCursor after,int limit){
        if(nodeId==null||nodeId.isBlank()||cutoff==null||limit<1||limit>100)throw new IllegalArgumentException("命令保留期发现参数不合法");
        var query=jdbc.sql("SELECT o.* FROM local_project_operations o JOIN local_projects p ON p.tenant_id=o.tenant_id AND p.id=o.project_id AND "+retentionOwnerJoin
                +" WHERE "+retentionNode+" AND o.kind='COMMAND' AND o.state IN ('PROPOSED','WAITING_APPROVAL','PREPARED','SUCCEEDED','FAILED','CANCELLED','UNKNOWN') AND o.created_at<=:cutoff"
                +(after==null?"":" AND (o.created_at>:afterTime OR (o.created_at=:afterTime AND (o.id>:afterId OR (o.id=:afterId AND o.tenant_id>:afterTenant))))")
                +" ORDER BY o.created_at,o.id,o.tenant_id LIMIT :limit")
                .param("node",nodeId).param("cutoff",Timestamp.from(cutoff)).param("limit",limit);
        if(after!=null)query=query.param("afterTime",Timestamp.from(after.createdAt())).param("afterId",after.operationId().toString()).param("afterTenant",after.tenantId().toString());
        return query.query(this::map).list();
    }

    @Override public boolean insertOperation(LocalProjectOperation operation) {
        if (operation.version() != 1 || operation.state() != LocalProjectOperation.State.PROPOSED)
            throw new IllegalArgumentException("操作只能以初始提案版本新增");
        return Boolean.TRUE.equals(transaction.execute(status -> {
        // 租户→项目→Run固定短锁序；重复操作先查后写，避免重复键破坏上层共享审计事务。
        if (jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant", operation.tenantId().toString())
                .query(String.class).optional().isEmpty()) return false;
        if (jdbc.sql("SELECT id FROM local_projects WHERE tenant_id=:tenant AND id=:id AND " + owner + " AND state='ACTIVE' FOR UPDATE")
                .param("tenant", operation.tenantId().toString()).param("id", operation.projectId().toString())
                .param("owner", operation.ownerId()).query(String.class).optional().isEmpty()) return false;
        if (jdbc.sql("SELECT COUNT(*) FROM local_project_operations WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", operation.tenantId().toString()).param("id", operation.id().toString()).query(Long.class).single() != 0) return false;
        var binding = new JdbcLocalProjectBindingRepository(jdbc, transaction)
                .run(operation.tenantId(), operation.ownerId(), operation.runId()).orElse(null);
        if (binding == null || !binding.projectId().equals(operation.projectId()) || !binding.agentId().equals(operation.agentId())
                || !binding.conversationId().equals(operation.conversationId())) return false;
            return jdbc.sql("""
                    INSERT INTO local_project_operations(id,tenant_id,owner_id,project_id,agent_id,conversation_id,run_id,
                      kind,manifest_digest,encrypted_payload,state,version,created_at,updated_at)
                    VALUES (:id,:tenant,:owner,:project,:agent,:conversation,:run,:kind,:digest,:payload,'PROPOSED',1,:created,:updated)
                    """).param("id", operation.id().toString()).param("tenant", operation.tenantId().toString()).param("owner", operation.ownerId())
                    .param("project", operation.projectId().toString()).param("agent", operation.agentId().toString())
                    .param("conversation", operation.conversationId().toString()).param("run", operation.runId().toString())
                    .param("kind", operation.kind().name()).param("digest", operation.digest()).param("payload", operation.encryptedPayload())
                    .param("created", Timestamp.from(operation.createdAt())).param("updated", Timestamp.from(operation.updatedAt())).update() == 1;
        }));
    }

    @Override public Optional<LocalProjectOperation> operation(UUID tenantId, String ownerId, UUID operationId) {
        return select(tenantId, ownerId, operationId, false);
    }
    /** 仅按已验证固定Run来源定位已派发操作；UNKNOWN保留原墓碑，不重新发现为可执行项。 */
    @Override public List<LocalProjectOperation> findRunningForRun(LocalProjectBinding binding, int limit) {
        if (binding == null || binding.runId() == null || limit < 1 || limit > 100) throw new IllegalArgumentException("失联操作发现参数无效");
        return jdbc.sql("SELECT * FROM local_project_operations WHERE tenant_id=:tenant AND " + owner
                + " AND project_id=:project AND agent_id=:agent AND conversation_id=:conversation AND run_id=:run"
                + " AND state='RUNNING' AND kind IN ('FILE_CHANGE','COMMAND') ORDER BY id LIMIT :limit")
                .param("tenant", binding.tenantId().toString()).param("owner", binding.ownerId()).param("project", binding.projectId().toString())
                .param("agent", binding.agentId().toString()).param("conversation", binding.conversationId().toString())
                .param("run", binding.runId().toString()).param("limit", limit).query(this::map).list();
    }

    /** 所有归属条件均在排序和截断之前执行；同时间以 UUID 字符串作稳定次级排序，不向客户端回传实体密文。 */
    @Override public List<LocalProjectOperation> listRun(UUID tenantId, String ownerId, UUID projectId, UUID runId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("操作列表上限必须在1到100之间");
        return jdbc.sql("SELECT * FROM local_project_operations WHERE tenant_id=:tenant AND " + owner
                + " AND project_id=:project AND run_id=:run ORDER BY created_at DESC,id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("project", projectId.toString())
                .param("run", runId.toString()).param("limit", limit).query(this::map).list();
    }

    @Override public boolean hasUncertainOperation(UUID tenantId,String ownerId,UUID projectId,UUID exceptOperationId){
        var query=jdbc.sql("SELECT COUNT(*) FROM local_project_operations WHERE tenant_id=:tenant AND project_id=:project AND "+owner+
                " AND state IN ('RUNNING','UNKNOWN')"+(exceptOperationId==null?"":" AND id<>:except"))
                .param("tenant",tenantId.toString()).param("project",projectId.toString()).param("owner",ownerId);
        if(exceptOperationId!=null)query=query.param("except",exceptOperationId.toString());
        return query.query(Long.class).single()!=0;
    }

    @Override public Optional<LocalProjectOperation> claimPrepared(UUID tenantId,String ownerId,UUID operationId,long expectedVersion,Instant now){
        return transaction.execute(status->{
            var initial=select(tenantId,ownerId,operationId,false).orElse(null);
            if(initial==null)return Optional.empty();
            var project=jdbc.sql("SELECT id FROM local_projects WHERE tenant_id=:tenant AND id=:project AND "+owner+" AND state='ACTIVE' FOR UPDATE")
                    .param("tenant",tenantId.toString()).param("project",initial.projectId().toString()).param("owner",ownerId)
                    .query(String.class).optional();
            if(project.isEmpty())return Optional.empty();
            var current=select(tenantId,ownerId,operationId,true).orElse(null);
            if(current==null||current.version()!=expectedVersion||current.state()!=LocalProjectOperation.State.PREPARED)return Optional.empty();
            // MySQL 8.4 默认 REPEATABLE READ 的普通查询会复用 initial 建立的旧快照；
            // 项目锁后的排他检查必须用锁定当前读，才能看见等待项目锁期间已提交的其他派发。
            // PostgreSQL 同样支持此语法；锁序保持项目→本操作→冲突操作，不在锁内执行本机副作用。
            // 依据：MySQL 8.4 手册 17.7.2.3/17.7.2.4；不能改回普通 COUNT 或先 LIMIT 后做主体过滤。
            boolean conflict=jdbc.sql("SELECT id FROM local_project_operations WHERE tenant_id=:tenant AND project_id=:project AND "+owner
                    +" AND state IN ('RUNNING','UNKNOWN') AND id<>:except ORDER BY id LIMIT 1 FOR UPDATE")
                    .param("tenant",tenantId.toString()).param("project",current.projectId().toString()).param("owner",ownerId)
                    .param("except",operationId.toString()).query(String.class).optional().isPresent();
            if(conflict)return Optional.empty();
            return advance(tenantId,ownerId,operationId,expectedVersion,LocalProjectOperation.State.RUNNING,now);
        });
    }

    @Override public Optional<LocalProjectOperation> advance(UUID tenantId, String ownerId, UUID operationId,
            long expectedVersion, LocalProjectOperation.State next, Instant now) {
        return transaction.execute(status -> {
            var current = select(tenantId, ownerId, operationId, true).orElse(null);
            if (current == null || current.version() != expectedVersion) return Optional.empty();
            var advanced = current.advance(next, now);
            int changed = jdbc.sql("UPDATE local_project_operations SET state=:state,version=:next,updated_at=:now "
                    + "WHERE tenant_id=:tenant AND id=:id AND " + owner + " AND version=:expected")
                    .param("state", next.name()).param("next", advanced.version()).param("now", Timestamp.from(now))
                    .param("tenant", tenantId.toString()).param("id", operationId.toString()).param("owner", ownerId)
                    .param("expected", expectedVersion).update();
            return changed == 1 ? Optional.of(advanced) : Optional.empty();
        });
    }

    private Optional<LocalProjectOperation> select(UUID tenant, String principal, UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM local_project_operations WHERE tenant_id=:tenant AND id=:id AND " + owner
                + (lock ? " FOR UPDATE" : "")).param("tenant", tenant.toString()).param("owner", principal)
                .param("id", id.toString()).query(this::map).optional();
    }

    @Override public boolean erasePayload(UUID tenantId,String ownerId,UUID operationId,long expectedVersion,String replacementCiphertext,Instant now){
        return Boolean.TRUE.equals(transaction.execute(status->{
            var current=select(tenantId,ownerId,operationId,true).orElse(null);
            if(current==null||current.version()!=expectedVersion||current.createdAt().plus(java.time.Duration.ofDays(7)).isAfter(now)
                    ||current.state()==LocalProjectOperation.State.PROPOSED||current.state()==LocalProjectOperation.State.WAITING_APPROVAL
                    ||current.state()==LocalProjectOperation.State.PREPARED||current.state()==LocalProjectOperation.State.RUNNING)return false;
            // 复用领域信封验证，但不把未知结果重新变成可派发状态，也不改原摘要。
            new LocalProjectOperation(current.id(),current.tenantId(),current.ownerId(),current.projectId(),current.agentId(),
                    current.conversationId(),current.runId(),current.kind(),current.digest(),replacementCiphertext,current.state(),
                    current.version(),current.createdAt(),now);
            return jdbc.sql("UPDATE local_project_operations SET encrypted_payload=:payload,version=version+1,updated_at=:now WHERE tenant_id=:tenant AND id=:id AND "+owner+" AND version=:expected")
                    .param("payload",replacementCiphertext).param("now",java.sql.Timestamp.from(now)).param("tenant",tenantId.toString())
                    .param("id",operationId.toString()).param("owner",ownerId).param("expected",expectedVersion).update()==1;
        }));
    }

    private LocalProjectOperation map(ResultSet row, int index) throws SQLException {
        return new LocalProjectOperation(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                row.getString("owner_id"), UUID.fromString(row.getString("project_id")), UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("conversation_id")), UUID.fromString(row.getString("run_id")),
                LocalProjectOperation.Kind.valueOf(row.getString("kind")), row.getString("manifest_digest"),
                row.getString("encrypted_payload"), LocalProjectOperation.State.valueOf(row.getString("state")),
                row.getLong("version"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}

