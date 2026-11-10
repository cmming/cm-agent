package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 设备项目绑定与来源见证在短事务内共同保存，不在锁内调用设备或模型。
 */
public final class JdbcClientProjectBindingRepository implements ClientProjectBindingRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String owner;

    public JdbcClientProjectBindingRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override public Optional<ClientProjectBinding> conversation(UUID tenantId, String ownerId,
            UUID conversationId) {
        return find("client_project_conversations", "conversation_id", tenantId, ownerId,
                conversationId, false);
    }

    @Override public Optional<ClientProjectBinding> run(UUID tenantId, String ownerId,
            UUID runId) {
        return find("client_project_runs", "run_id", tenantId, ownerId, runId, true);
    }

    @Override public boolean insertConversation(ClientProjectBinding binding) {
        if (binding.runId() != null) throw new IllegalArgumentException("会话绑定不能带运行标识");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!projectAvailable(binding, false)) return false;
            String creator = JdbcOwnerPredicate.equal(jdbc, "created_by", ":owner");
            int protocol = jdbc.sql("SELECT workspace_protocol FROM conversations WHERE tenant_id=:tenant"
                    + " AND id=:id AND agent_id=:agent AND " + creator + " FOR UPDATE")
                    .param("tenant", binding.tenantId().toString())
                    .param("id", binding.conversationId().toString())
                    .param("agent", binding.agentId().toString()).param("owner", binding.ownerId())
                    .query(Integer.class).optional().orElse(-1);
            if (protocol != 0) return false;
            insert("client_project_conversations", binding, false);
            jdbc.sql("UPDATE conversations SET workspace_protocol=5 WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", binding.tenantId().toString())
                    .param("id", binding.conversationId().toString()).update();
            return protocol("conversations", binding) == 5;
        }));
    }

    @Override public boolean insertRun(ClientProjectBinding binding) {
        if (binding.runId() == null) throw new IllegalArgumentException("运行绑定必须带运行标识");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!projectAvailable(binding, true)) return false;
            String principal = JdbcOwnerPredicate.equal(jdbc, "principal_id", ":owner");
            int protocol = jdbc.sql("SELECT workspace_protocol FROM runs WHERE tenant_id=:tenant"
                    + " AND id=:id AND agent_id=:agent AND run_kind='NORMAL' AND " + principal
                    + " FOR UPDATE").param("tenant", binding.tenantId().toString())
                    .param("id", binding.runId().toString()).param("agent", binding.agentId().toString())
                    .param("owner", binding.ownerId()).query(Integer.class).optional().orElse(-1);
            if (protocol != 0 && protocol != ClientProjectBinding.PROTOCOL) return false;
            var source = conversation(binding.tenantId(), binding.ownerId(), binding.conversationId())
                    .orElse(null);
            if (!sameOrigin(source, binding)) return false;
            insert("client_project_runs", binding, true);
            jdbc.sql("UPDATE runs SET workspace_protocol=5 WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", binding.tenantId().toString())
                    .param("id", binding.runId().toString()).update();
            return protocol("runs", binding) == 5;
        }));
    }

    @Override public List<Conversation> listConversations(UUID tenantId, String ownerId,
            UUID projectId, ConversationPageRequest page) {
        String projectOwner = JdbcOwnerPredicate.equal(jdbc, "p.owner_id", ":owner");
        String creator = JdbcOwnerPredicate.equal(jdbc, "c.created_by", ":owner");
        var query = jdbc.sql("SELECT c.* FROM client_project_conversations p"
                + " JOIN conversations c ON c.tenant_id=p.tenant_id AND c.id=p.conversation_id"
                + " AND c.agent_id=p.agent_id WHERE p.tenant_id=:tenant AND p.project_id=:project"
                + " AND " + projectOwner + " AND " + creator + " AND c.workspace_protocol=5"
                + (page.beforeUpdatedAt() == null ? "" : " AND (c.updated_at<:before OR (c.updated_at=:before AND c.id<:beforeId))")
                + " ORDER BY c.updated_at DESC,c.id DESC LIMIT :limit")
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("project", projectId.toString()).param("limit", page.limit());
        if (page.beforeUpdatedAt() != null) {
            query = query.param("before", Timestamp.from(page.beforeUpdatedAt()))
                    .param("beforeId", page.beforeId().toString());
        }
        return query.query((row, index) -> new Conversation(
                UUID.fromString(row.getString("id")), UUID.fromString(row.getString("tenant_id")),
                UUID.fromString(row.getString("agent_id")), row.getString("title"),
                row.getString("created_by"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant())).list();
    }

    private Optional<ClientProjectBinding> find(String table, String column, UUID tenant,
            String principal, UUID id, boolean run) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE tenant_id=:tenant AND " + column
                + "=:id AND " + owner).param("tenant", tenant.toString())
                .param("owner", principal).param("id", id.toString())
                .query((row, index) -> map(row, run)).optional();
    }

    private boolean projectAvailable(ClientProjectBinding binding, boolean run) {
        return jdbc.sql("SELECT COUNT(*) FROM client_projects p WHERE p.tenant_id=:tenant"
                + " AND p.id=:project AND p.state='ACTIVE' AND p.device_id=:device"
                + " AND p.client_root_id=:root AND p.root_identity=:identity"
                + " AND p.version=:version AND " + JdbcOwnerPredicate.equal(jdbc, "p.owner_id", ":owner"))
                .param("tenant", binding.tenantId().toString())
                .param("project", binding.projectId().toString())
                .param("device", binding.deviceId().toString())
                .param("root", binding.clientRootId().toString())
                .param("identity", binding.rootIdentity())
                .param("version", binding.projectVersion()).param("owner", binding.ownerId())
                .query(Long.class).single() == 1;
    }

    private void insert(String table, ClientProjectBinding value, boolean run) {
        var sql = jdbc.sql("INSERT INTO " + table + "(tenant_id,owner_id,project_id,agent_id,"
                + "conversation_id," + (run ? "run_id," : "") + "device_id,client_root_id,"
                + "root_identity,project_version,created_at) VALUES (:tenant,:owner,:project,:agent,"
                + ":conversation," + (run ? ":run," : "")
                + ":device,:root,:identity,:version,:created)")
                .param("tenant", value.tenantId().toString()).param("owner", value.ownerId())
                .param("project", value.projectId().toString()).param("agent", value.agentId().toString())
                .param("conversation", value.conversationId().toString())
                .param("device", value.deviceId().toString())
                .param("root", value.clientRootId().toString())
                .param("identity", value.rootIdentity()).param("version", value.projectVersion())
                .param("created", Timestamp.from(value.createdAt()));
        if (run) sql = sql.param("run", value.runId().toString());
        sql.update();
    }

    private int protocol(String table, ClientProjectBinding binding) {
        return jdbc.sql("SELECT workspace_protocol FROM " + table + " WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", binding.tenantId().toString())
                .param("id", table.equals("runs") ? binding.runId().toString()
                        : binding.conversationId().toString()).query(Integer.class).single();
    }

    private static boolean sameOrigin(ClientProjectBinding source, ClientProjectBinding binding) {
        return source != null && source.projectId().equals(binding.projectId())
                && source.agentId().equals(binding.agentId())
                && source.deviceId().equals(binding.deviceId())
                && source.clientRootId().equals(binding.clientRootId())
                && source.rootIdentity().equals(binding.rootIdentity())
                && source.projectVersion() == binding.projectVersion();
    }

    private static ClientProjectBinding map(ResultSet row, boolean run) throws SQLException {
        return new ClientProjectBinding(UUID.fromString(row.getString("tenant_id")),
                row.getString("owner_id"), UUID.fromString(row.getString("project_id")),
                UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("conversation_id")),
                run ? UUID.fromString(row.getString("run_id")) : null,
                UUID.fromString(row.getString("device_id")),
                UUID.fromString(row.getString("client_root_id")), row.getString("root_identity"),
                row.getLong("project_version"), row.getTimestamp("created_at").toInstant());
    }
}
