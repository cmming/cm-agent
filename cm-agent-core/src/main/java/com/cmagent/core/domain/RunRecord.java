package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RunRecord(
        UUID id,
        UUID tenantId,
        UUID agentId,
        String principalId,
        RunStatus status,
        String input,
        String output,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
    public RunRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        input = input == null ? "" : input;
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        if (status == RunStatus.RUNNING && finishedAt != null) {
            throw new IllegalArgumentException("RUNNING 状态不能有 finishedAt");
        }
        if (status != RunStatus.RUNNING && finishedAt == null) {
            throw new IllegalArgumentException("终态必须有 finishedAt");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
    }

    public static RunRecord create(
            UUID id,
            UUID tenantId,
            UUID agentId,
            String principalId,
            String input,
            Instant startedAt
    ) {
        return new RunRecord(id, tenantId, agentId, principalId, RunStatus.RUNNING, input, "", "", startedAt, null);
    }

    public RunRecord complete(RunStatus status, String output, String errorMessage, Instant finishedAt) {
        if (status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("finalStatus 不能为 RUNNING");
        }
        if (this.status != RunStatus.RUNNING) {
            throw new IllegalStateException("只能完成 RUNNING 状态的运行");
        }
        return new RunRecord(
                id,
                tenantId,
                agentId,
                principalId,
                status,
                input,
                output,
                errorMessage,
                startedAt,
                Objects.requireNonNull(finishedAt, "finishedAt 不能为空")
        );
    }
}
