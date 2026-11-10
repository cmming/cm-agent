package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectCommand;
import com.cmagent.core.repository.ClientProjectCommandRepository;
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

/** JDBC 命令仓储；所有查询同时限定可信 tenant 与精确 owner。 */
public final class JdbcClientProjectCommandRepository
        implements ClientProjectCommandRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String owner;

    /**
     * @param jdbc 与宿主 MySQL/PostgreSQL 方言一致的 JDBC 客户端
     * @param transaction 仅用于锁定命令行的短事务
     */
    public JdbcClientProjectCommandRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.owner = JdbcOwnerPredicate.equal(jdbc, "owner_id", ":owner");
    }

    @Override public boolean insert(ClientProjectCommand command) {
        if (command.state() != ClientProjectCommand.State.PROPOSED || command.version() != 1) {
            throw new IllegalArgumentException("命令首次写入必须为 PROPOSED/version=1");
        }
        return jdbc.sql("""
                INSERT INTO client_project_commands(
                  id,tenant_id,owner_id,project_id,agent_id,conversation_id,run_id,
                  plan_digest,display_digest,display_exact,encrypted_plan,encrypted_output,
                  output_available,truncated,exit_code,state,stop_requested,sequence_no,version,
                  created_at,updated_at)
                VALUES (:id,:tenant,:owner,:project,:agent,:conversation,:run,:planDigest,
                  :displayDigest,:displayExact,:plan,:output,:available,:truncated,:exitCode,
                  :state,:stopRequested,:sequence,1,:created,:updated)
                """).param("id", command.id().toString())
                .param("tenant", command.tenantId().toString()).param("owner", command.ownerId())
                .param("project", command.projectId().toString())
                .param("agent", command.agentId().toString())
                .param("conversation", command.conversationId().toString())
                .param("run", command.runId().toString())
                .param("planDigest", command.planDigest())
                .param("displayDigest", command.displayDigest())
                .param("displayExact", command.displayExact())
                .param("plan", command.encryptedPlan())
                .param("output", command.encryptedOutput())
                .param("available", command.outputAvailable())
                .param("truncated", command.truncated())
                .param("exitCode", command.exitCode()).param("state", command.state().name())
                .param("stopRequested", command.stopRequested())
                .param("sequence", command.sequence())
                .param("created", Timestamp.from(command.createdAt()))
                .param("updated", Timestamp.from(command.updatedAt())).update() == 1;
    }

    @Override public Optional<ClientProjectCommand> find(UUID tenantId, String ownerId,
            UUID commandId) {
        return find(tenantId, ownerId, commandId, false);
    }

    @Override public Optional<ClientProjectCommand> lock(UUID tenantId, String ownerId,
            UUID commandId) {
        return transaction.execute(status -> find(tenantId, ownerId, commandId, true));
    }

    private Optional<ClientProjectCommand> find(UUID tenantId, String ownerId, UUID commandId,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.sql("SELECT * FROM client_project_commands WHERE tenant_id=:tenant"
                + " AND id=:id AND " + owner + suffix)
                .param("tenant", tenantId.toString()).param("owner", ownerId)
                .param("id", commandId.toString()).query(this::map).optional();
    }

    @Override public Optional<ClientProjectCommand> update(UUID tenantId, String ownerId,
            UUID commandId, long expectedVersion, ClientProjectCommand next) {
        if (!tenantId.equals(next.tenantId()) || !ownerId.equals(next.ownerId())
                || !commandId.equals(next.id()) || next.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("命令更新上下文不一致");
        }
        int changed = jdbc.sql("""
                UPDATE client_project_commands SET encrypted_output=:output,
                  output_available=:available,truncated=:truncated,exit_code=:exitCode,
                  state=:state,stop_requested=:stopRequested,sequence_no=:sequence,
                  version=:version,updated_at=:updated
                WHERE tenant_id=:tenant AND id=:id AND version=:expected AND
                """.replace("\n", " ") + owner)
                .param("output", next.encryptedOutput())
                .param("available", next.outputAvailable()).param("truncated", next.truncated())
                .param("exitCode", next.exitCode()).param("state", next.state().name())
                .param("stopRequested", next.stopRequested()).param("sequence", next.sequence())
                .param("version", next.version()).param("updated", Timestamp.from(next.updatedAt()))
                .param("tenant", tenantId.toString()).param("id", commandId.toString())
                .param("expected", expectedVersion).param("owner", ownerId).update();
        return changed == 1 ? Optional.of(next) : Optional.empty();
    }

    @Override public List<ClientProjectCommand> listUnsettledForRun(UUID tenantId,
            String ownerId, UUID runId) {
        return jdbc.sql("SELECT * FROM client_project_commands WHERE tenant_id=:tenant"
                + " AND run_id=:run AND state IN ('QUEUED','RUNNING','STOP_REQUESTED','UNKNOWN')"
                + " AND " + owner + " ORDER BY created_at,id")
                .param("tenant", tenantId.toString()).param("run", runId.toString())
                .param("owner", ownerId).query(this::map).list();
    }

    @Override public List<ClientProjectCommand> listForRun(UUID tenantId, String ownerId,
            UUID runId) {
        return jdbc.sql("SELECT * FROM client_project_commands WHERE tenant_id=:tenant"
                + " AND run_id=:run AND " + owner + " ORDER BY created_at,id")
                .param("tenant", tenantId.toString()).param("run", runId.toString())
                .param("owner", ownerId).query(this::map).list();
    }

    @Override public boolean hasUnsettledForProject(UUID tenantId, String ownerId,
            UUID projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_commands WHERE tenant_id=:tenant"
                + " AND project_id=:project AND state IN"
                + " ('QUEUED','RUNNING','STOP_REQUESTED','UNKNOWN') AND " + owner)
                .param("tenant", tenantId.toString()).param("project", projectId.toString())
                .param("owner", ownerId).query(Long.class).single() != 0;
    }

    @Override public boolean hasUnresolvedTask(UUID tenantId, String ownerId, UUID commandId) {
        return jdbc.sql("SELECT COUNT(*) FROM client_project_tasks WHERE tenant_id=:tenant"
                + " AND command_id=:command AND state IN"
                + " ('PENDING','DISPATCHED','UNKNOWN') AND " + owner)
                .param("tenant", tenantId.toString()).param("command", commandId.toString())
                .param("owner", ownerId).query(Long.class).single() != 0;
    }

    @Override public int expireUnsettled(UUID tenantId, String ownerId, UUID runId,
            Instant cutoff, Instant now) {
        return jdbc.sql("UPDATE client_project_commands SET state='UNKNOWN',"
                + "version=version+1,updated_at=:now WHERE tenant_id=:tenant AND run_id=:run"
                + " AND state IN ('QUEUED','RUNNING','STOP_REQUESTED')"
                + " AND updated_at<=:cutoff AND " + owner)
                .param("now", Timestamp.from(now)).param("cutoff", Timestamp.from(cutoff))
                .param("tenant", tenantId.toString()).param("run", runId.toString())
                .param("owner", ownerId).update();
    }

    private ClientProjectCommand map(ResultSet row, int index) throws SQLException {
        Timestamp created = row.getTimestamp("created_at");
        Timestamp updated = row.getTimestamp("updated_at");
        int exitCode = row.getInt("exit_code");
        return new ClientProjectCommand(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("tenant_id")), row.getString("owner_id"),
                UUID.fromString(row.getString("project_id")),
                UUID.fromString(row.getString("agent_id")),
                UUID.fromString(row.getString("conversation_id")),
                UUID.fromString(row.getString("run_id")), row.getString("plan_digest"),
                row.getString("display_digest"), row.getBoolean("display_exact"),
                row.getString("encrypted_plan"), row.getString("encrypted_output"),
                row.getBoolean("output_available"), row.getBoolean("truncated"),
                row.wasNull() ? null : exitCode, ClientProjectCommand.State.valueOf(
                        row.getString("state")), row.getBoolean("stop_requested"),
                row.getLong("sequence_no"), row.getLong("version"), created.toInstant(),
                updated.toInstant());
    }
}
