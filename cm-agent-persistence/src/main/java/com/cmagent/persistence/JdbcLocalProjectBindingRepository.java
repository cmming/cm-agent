package com.cmagent.persistence;

import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import java.util.List;
import com.cmagent.core.repository.LocalProjectBindingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 项目绑定与来源见证在同一短事务保存；锁只保护元数据，不包含文件、桌面或模型调用。
 * 普通会话/Run仓储尚未接线协议4时，不允许借这些记录回退旧DEFAULT。
 */
public final class JdbcLocalProjectBindingRepository implements LocalProjectBindingRepository {
    /** 当前受信数据源的命名参数入口。 */
    private final JdbcClient jdbc;
    /** 与业务服务共享事务管理器；行锁在绑定/见证提交前保持。 */
    private final TransactionTemplate transaction;
    /** 精确owner过滤，固定列名不接受外部SQL。 */
    private final String owner;

    /**
     * @param jdbc 可信数据源的命名参数入口，不能为null
     * @param transaction 必须绑定与jdbc相同数据源；调用方不得在锁内执行本机I/O或模型
     */
    public JdbcLocalProjectBindingRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override public Optional<LocalProjectBinding> conversation(UUID tenantId, String ownerId, UUID conversationId) {
        return find("local_project_conversations", "conversation_id", tenantId, ownerId, conversationId, false);
    }
    /** 已知绑定本身就是阻断见证；不按来源头/kind/当前Run主体过滤，以免损坏存储隐藏仍活动的原绑定。 */
    @Override public boolean hasActiveRuns(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM local_project_runs b JOIN runs r ON r.tenant_id=b.tenant_id AND r.id=b.run_id"
                + " WHERE b.tenant_id=:tenant AND b.project_id=:project AND " + JdbcOwnerPredicate.equal(jdbc, "b.owner_id", ":owner")
                + " AND r.status IN ('RUNNING','WAITING_APPROVAL','CANCELLING')")
                .param("tenant", tenantId.toString()).param("owner", ownerId).param("project", projectId.toString()).query(Long.class).single() > 0;
    }
    /** 项目/固定会话/运行完整见证先于LIMIT，大小写不同的owner或node绝不作为本机失联运行发现。 */
    @Override public List<LocalProjectBinding> findInterruptedRuns(String nodeId, int limit) {
        if (nodeId == null || nodeId.isBlank() || limit < 1 || limit > 100) throw new IllegalArgumentException("冷启动发现参数无效");
        String sameOwner = JdbcOwnerPredicate.equal(jdbc, "b.owner_id", "p.owner_id")
                + " AND " + JdbcOwnerPredicate.equal(jdbc, "r.principal_id", "b.owner_id")
                + " AND " + JdbcOwnerPredicate.equal(jdbc, "c.created_by", "b.owner_id")
                + " AND " + JdbcOwnerPredicate.equal(jdbc, "cb.owner_id", "b.owner_id");
        String sameNode = JdbcOwnerPredicate.equal(jdbc, "p.node_id", ":node")
                + " AND " + JdbcOwnerPredicate.equal(jdbc, "b.node_id", ":node")
                + " AND " + JdbcOwnerPredicate.equal(jdbc, "cb.node_id", ":node");
        return jdbc.sql("SELECT b.* FROM local_project_runs b JOIN local_projects p ON p.tenant_id=b.tenant_id AND p.id=b.project_id"
                + " AND p.root_binding_id=b.root_binding_id AND p.root_identity=b.root_identity"
                + " JOIN runs r ON r.tenant_id=b.tenant_id AND r.id=b.run_id AND r.agent_id=b.agent_id"
                + " JOIN local_project_conversations cb ON cb.tenant_id=b.tenant_id AND cb.conversation_id=b.conversation_id"
                + " AND cb.project_id=b.project_id AND cb.agent_id=b.agent_id AND cb.root_binding_id=b.root_binding_id AND cb.root_identity=b.root_identity"
                + " JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id AND c.agent_id=b.agent_id"
                + " WHERE " + sameOwner + " AND " + sameNode
                + " AND r.workspace_protocol=4 AND c.workspace_protocol=4 AND r.run_kind='NORMAL' AND r.status IN ('RUNNING','CANCELLING')"
                + " ORDER BY b.tenant_id,b.run_id LIMIT :limit")
                .param("node", nodeId).param("limit", limit).query((row, index) -> map(row, true)).list();
    }
    @Override public Optional<LocalProjectBinding> run(UUID tenantId, String ownerId, UUID runId) {
        return find("local_project_runs", "run_id", tenantId, ownerId, runId, true);
    }

    /** 精确owner与来源见证同时匹配，不能先截断再隐藏，且不把缺失绑定的会话当作旧来源。 */
    @Override public List<Conversation> listConversations(UUID tenantId, String ownerId, UUID projectId, ConversationPageRequest page) {
        String privateOwner = JdbcOwnerPredicate.equal(jdbc, "p.owner_id", ":owner");
        String creator = JdbcOwnerPredicate.equal(jdbc, "c.created_by", ":owner");
        var query = jdbc.sql("SELECT c.* FROM local_project_conversations p JOIN conversations c ON c.tenant_id=p.tenant_id"
                + " AND c.id=p.conversation_id AND c.agent_id=p.agent_id WHERE p.tenant_id=:tenant AND p.project_id=:project AND "
                + privateOwner + " AND " + creator + " AND c.workspace_protocol=4"
                + (page.beforeUpdatedAt() == null ? "" : " AND (c.updated_at<:before OR (c.updated_at=:before AND c.id<:beforeId))")
                + " ORDER BY c.updated_at DESC,c.id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("project", projectId.toString()).param("owner", ownerId).param("limit", page.limit());
        if (page.beforeUpdatedAt() != null) query = query.param("before", Timestamp.from(page.beforeUpdatedAt())).param("beforeId", page.beforeId().toString());
        return query.query((row, index) -> new Conversation(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                UUID.fromString(row.getString("agent_id")), row.getString("title"), row.getString("created_by"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant())).list();
    }

    private Optional<LocalProjectBinding> find(String table, String column, UUID tenant, String principal, UUID id, boolean run) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE tenant_id=:tenant AND " + column + "=:id AND " + owner)
                .param("tenant", tenant.toString()).param("owner", principal).param("id", id.toString())
                .query((row, index) -> map(row, run)).optional();
    }

    @Override public boolean insertConversation(LocalProjectBinding binding) {
        if (binding.runId() != null) throw new IllegalArgumentException("会话绑定不能带运行标识");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var project = lockProject(binding);
            if (!matches(project, binding) || project.version() != binding.projectVersion()) return false;
            String creator = JdbcOwnerPredicate.equal(jdbc, "created_by", ":owner");
            var protocol = jdbc.sql("SELECT workspace_protocol FROM conversations WHERE tenant_id=:tenant AND id=:id AND agent_id=:agent AND "
                    + creator + " FOR UPDATE").param("tenant", binding.tenantId().toString())
                    .param("id", binding.conversationId().toString()).param("agent", binding.agentId().toString())
                    .param("owner", binding.ownerId()).query(Integer.class).optional();
            if (protocol.isEmpty() || protocol.get() != 0) return false;
            if (jdbc.sql("SELECT COUNT(*) FROM messages WHERE tenant_id=:tenant AND conversation_id=:id")
                    .param("tenant", binding.tenantId().toString()).param("id", binding.conversationId().toString()).query(Long.class).single() != 0) return false;
            if (jdbc.sql("SELECT COUNT(*) FROM conversation_workspace_bindings WHERE tenant_id=:tenant AND resource_id=:id")
                    .param("tenant", binding.tenantId().toString()).param("id", binding.conversationId().toString()).query(Long.class).single() != 0) return false;
            if (conversation(binding.tenantId(), binding.ownerId(), binding.conversationId()).isPresent()) return false;
            insert("local_project_conversations", binding, false);
            jdbc.sql("UPDATE conversations SET workspace_protocol=4 WHERE tenant_id=:tenant AND id=:id AND workspace_protocol=0")
                    .param("tenant", binding.tenantId().toString()).param("id", binding.conversationId().toString()).update();
            verifyProtocol("conversations", binding.tenantId(), binding.conversationId());
            return true;
        }));
    }

    @Override public boolean insertRun(LocalProjectBinding binding) {
        if (binding.runId() == null) throw new IllegalArgumentException("运行绑定必须带运行标识");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var project = lockProject(binding);
            if (!matches(project, binding)) return false;
            String principal = JdbcOwnerPredicate.equal(jdbc, "principal_id", ":owner");
            var protocol = jdbc.sql("SELECT workspace_protocol FROM runs WHERE tenant_id=:tenant AND id=:id AND agent_id=:agent AND run_kind='NORMAL' AND "
                    + principal + " FOR UPDATE").param("tenant", binding.tenantId().toString()).param("id", binding.runId().toString())
                    .param("agent", binding.agentId().toString()).param("owner", binding.ownerId()).query(Integer.class).optional();
            if (protocol.isEmpty() || protocol.get() != 0 && protocol.get() != LocalProjectBinding.PROTOCOL) return false;
            var source = conversation(binding.tenantId(), binding.ownerId(), binding.conversationId()).orElse(null);
            if (!sameOrigin(source, binding) || run(binding.tenantId(), binding.ownerId(), binding.runId()).isPresent()) return false;
            if (jdbc.sql("SELECT COUNT(*) FROM run_workspace_selections WHERE tenant_id=:tenant AND resource_id=:id")
                    .param("tenant", binding.tenantId().toString()).param("id", binding.runId().toString()).query(Long.class).single() != 0) return false;
            insert("local_project_runs", binding, true);
            jdbc.sql("UPDATE runs SET workspace_protocol=4 WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", binding.tenantId().toString()).param("id", binding.runId().toString()).update();
            verifyProtocol("runs", binding.tenantId(), binding.runId());
            return true;
        }));
    }

    /** 不依赖MySQL影响行数配置；同一事务内必须确认见证存在，否则让绑定和见证一起回滚。 */
    private void verifyProtocol(String table, UUID tenant, UUID id) {
        int saved = jdbc.sql("SELECT workspace_protocol FROM " + table + " WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(Integer.class).single();
        if (saved != LocalProjectBinding.PROTOCOL) throw new IllegalStateException("项目来源见证未能保存");
    }

    /** 固定锁序为项目→会话/Run，归档与创建绑定不能在检查后交错提交。 */
    private LocalProject lockProject(LocalProjectBinding binding) {
        var locked = jdbc.sql("SELECT id FROM local_projects WHERE tenant_id=:tenant AND id=:id AND " + owner + " FOR UPDATE")
                .param("tenant", binding.tenantId().toString()).param("owner", binding.ownerId())
                .param("id", binding.projectId().toString()).query(String.class).optional();
        return locked.isEmpty() ? null : new JdbcLocalProjectRepository(jdbc, transaction)
                .find(binding.tenantId(), binding.ownerId(), binding.projectId()).orElse(null);
    }

    private static boolean matches(LocalProject project, LocalProjectBinding binding) {
        return project != null && project.state() == LocalProject.State.ACTIVE && project.rootBindingId().equals(binding.rootBindingId())
                && project.rootIdentity().equals(binding.rootIdentity()) && project.nodeId().equals(binding.nodeId());
    }

    private static boolean sameOrigin(LocalProjectBinding source, LocalProjectBinding binding) {
        return source != null && source.projectId().equals(binding.projectId()) && source.agentId().equals(binding.agentId())
                && source.rootBindingId().equals(binding.rootBindingId()) && source.rootIdentity().equals(binding.rootIdentity())
                && source.nodeId().equals(binding.nodeId()) && source.projectVersion() == binding.projectVersion();
    }

    /** 固定表名仅从本类调用，全部身份参数从已校验领域对象绑定，不拼入SQL。 */
    private void insert(String table, LocalProjectBinding value, boolean run) {
        var sql = jdbc.sql("INSERT INTO " + table + "(tenant_id,owner_id,project_id,agent_id,conversation_id,"
                + (run ? "run_id," : "") + "root_binding_id,root_identity,node_id,project_version,created_at)"
                + " VALUES (:tenant,:owner,:project,:agent,:conversation," + (run ? ":run," : "")
                + ":root,:identity,:node,:version,:created)")
                .param("tenant", value.tenantId().toString()).param("owner", value.ownerId()).param("project", value.projectId().toString())
                .param("agent", value.agentId().toString()).param("conversation", value.conversationId().toString())
                .param("root", value.rootBindingId().toString()).param("identity", value.rootIdentity()).param("node", value.nodeId())
                .param("version", value.projectVersion()).param("created", Timestamp.from(value.createdAt()));
        if (run) sql = sql.param("run", value.runId().toString());
        sql.update();
    }

    private static LocalProjectBinding map(ResultSet row, boolean run) throws SQLException {
        return new LocalProjectBinding(UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("project_id")), UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("conversation_id")), run ? UUID.fromString(row.getString("run_id")) : null,
                UUID.fromString(row.getString("root_binding_id")), row.getString("root_identity"), row.getString("node_id"),
                row.getLong("project_version"), row.getTimestamp("created_at").toInstant());
    }
}

