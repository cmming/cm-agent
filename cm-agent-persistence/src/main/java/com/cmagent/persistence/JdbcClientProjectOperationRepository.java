package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectOperation;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 客户端项目操作元数据；原始变更内容只保存认证加密计划。 */
public final class JdbcClientProjectOperationRepository
        implements ClientProjectOperationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String owner;

    public JdbcClientProjectOperationRepository(JdbcClient jdbc,
            TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override
    public boolean insert(ClientProjectOperation operation) {
        if (operation.version() != 1
                || operation.state() != ClientProjectOperation.State.PROPOSED) {
            throw new IllegalArgumentException("客户端项目操作只能以 PROPOSED 新增");
        }
        return jdbc.sql("""
                INSERT INTO client_project_operations(id,tenant_id,owner_id,project_id,
                  agent_id,conversation_id,run_id,undo_of,plan_digest,display_digest,
                  display_exact,encrypted_plan,state,version,created_at,updated_at)
                VALUES (:id,:tenant,:owner,:project,:agent,:conversation,:run,:undo,
                  :digest,:display,:exact,:plan,'PROPOSED',1,:created,:updated)
                """).param("id", operation.id().toString())
                .param("tenant", operation.tenantId().toString())
                .param("owner", operation.ownerId())
                .param("project", operation.projectId().toString())
                .param("agent", operation.agentId().toString())
                .param("conversation", operation.conversationId().toString())
                .param("run", operation.runId().toString())
                .param("undo", operation.undoOf() == null ? null : operation.undoOf().toString())
                .param("digest", operation.planDigest())
                .param("display", operation.displayDigest())
                .param("exact", operation.displayExact())
                .param("plan", operation.encryptedPlan())
                .param("created", Timestamp.from(operation.createdAt()))
                .param("updated", Timestamp.from(operation.updatedAt())).update() == 1;
    }

    @Override
    public Optional<ClientProjectOperation> find(UUID tenantId, String ownerId,
            UUID operationId) {
        return select(tenantId, ownerId, operationId, false);
    }

    @Override
    public Optional<ClientProjectOperation> lock(UUID tenantId, String ownerId,
            UUID operationId) {
        return select(tenantId, ownerId, operationId, true);
    }

    @Override
    public Optional<ClientProjectOperation> advance(UUID tenantId, String ownerId,
            UUID operationId, long expectedVersion, ClientProjectOperation.State next,
            Instant now) {
        return transaction.execute(status -> {
            ClientProjectOperation current = select(tenantId, ownerId, operationId, true)
                    .orElse(null);
            if (current == null || current.version() != expectedVersion) {
                return Optional.empty();
            }
            ClientProjectOperation advanced = current.advance(next, now);
            int changed = jdbc.sql("UPDATE client_project_operations SET state=:state,"
                    + "version=:next,updated_at=:now WHERE tenant_id=:tenant AND id=:id"
                    + " AND version=:expected AND " + owner)
                    .param("state", next.name()).param("next", advanced.version())
                    .param("now", Timestamp.from(now))
                    .param("tenant", tenantId.toString()).param("id", operationId.toString())
                    .param("expected", expectedVersion).param("owner", ownerId).update();
            return changed == 1 ? Optional.of(advanced) : Optional.empty();
        });
    }

    @Override
    public boolean hasUncertain(UUID tenantId, String ownerId, UUID projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_operations WHERE tenant_id=:tenant"
                + " AND project_id=:project AND state IN ('APPLYING','UNKNOWN') AND " + owner)
                .param("tenant", tenantId.toString()).param("project", projectId.toString())
                .param("owner", ownerId).query(Long.class).single() != 0;
    }

    private Optional<ClientProjectOperation> select(UUID tenantId, String ownerId,
            UUID operationId, boolean lock) {
        return jdbc.sql("SELECT * FROM client_project_operations WHERE tenant_id=:tenant"
                + " AND id=:id AND " + owner + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenantId.toString()).param("id", operationId.toString())
                .param("owner", ownerId)
                .query((row, index) -> new ClientProjectOperation(
                        UUID.fromString(row.getString("id")),
                        UUID.fromString(row.getString("tenant_id")),
                        row.getString("owner_id"),
                        UUID.fromString(row.getString("project_id")),
                        UUID.fromString(row.getString("agent_id")),
                        UUID.fromString(row.getString("conversation_id")),
                        UUID.fromString(row.getString("run_id")),
                        row.getString("undo_of") == null ? null
                                : UUID.fromString(row.getString("undo_of")),
                        row.getString("plan_digest"), row.getString("display_digest"),
                        row.getBoolean("display_exact"), row.getString("encrypted_plan"),
                        ClientProjectOperation.State.valueOf(row.getString("state")),
                        row.getLong("version"),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant()))
                .optional();
    }
}
