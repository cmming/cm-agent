package com.cmagent.core.repository;

import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository {
    /**
     * Persists a run only when {@code run.tenantId()} matches {@code tenantId}.
     */
    RunRecord save(UUID tenantId, RunRecord run);

    /**
     * Completes only a matching {@link RunStatus#RUNNING} record in the supplied tenant.
     */
    RunRecord complete(
            UUID tenantId,
            UUID runId,
            RunStatus status,
            String output,
            String errorMessage,
            Instant finishedAt
    );

    Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId);

    /**
     * Lists runs in {@code startedAt DESC, id DESC} order using a validated page request. Implementations
     * return only rows with {@code (startedAt, id)} strictly less than the non-null cursor tuple.
     */
    List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest);

    static Comparator<RunRecord> keysetOrder() {
        return Comparator.comparing(RunRecord::startedAt)
                .reversed()
                .thenComparing(RunRecord::id, (left, right) -> compareIdsByDatabaseOrder(right, left));
    }

    /**
     * Compares UUIDs in the canonical lowercase representation stored in {@code runs.id CHAR(36)}.
     */
    static int compareIdsByDatabaseOrder(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    static boolean isStrictlyBeforeCursor(RunRecord run, RunPageRequest pageRequest) {
        Objects.requireNonNull(run, "run 不能为空");
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        if (pageRequest.beforeStartedAt() == null) {
            return true;
        }
        int startedAtComparison = run.startedAt().compareTo(pageRequest.beforeStartedAt());
        return startedAtComparison < 0
                || (startedAtComparison == 0 && compareIdsByDatabaseOrder(run.id(), pageRequest.beforeId()) < 0);
    }
}
