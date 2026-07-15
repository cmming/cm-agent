package com.cmagent.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolCallRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRecordTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant STARTED_AT = Instant.parse("2026-07-14T00:00:00Z");


    @Test
    void createRejectsBlankPrincipal() {
        assertThatThrownBy(() -> RunRecord.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                " ",
                "input",
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("principalId 不能为空");
    }

    @Test
    void createProducesUnfinishedRunningRunAndNormalizesNullText() {
        RunRecord run = RunRecord.create(ID, TENANT_ID, AGENT_ID, "principal", null, STARTED_AT);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.finishedAt()).isNull();
        assertThat(run.input()).isEmpty();
        assertThat(run.output()).isEmpty();
        assertThat(run.errorMessage()).isEmpty();
    }

    @Test
    void constructorRejectsRunningRunWithFinishedAt() {
        assertThatThrownBy(() -> new RunRecord(
                ID,
                TENANT_ID,
                AGENT_ID,
                "principal",
                RunStatus.RUNNING,
                "input",
                "",
                "",
                STARTED_AT,
                STARTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RUNNING 状态不能有 finishedAt");
    }

    @Test
    void constructorRejectsTerminalRunWithoutFinishedAt() {
        assertThatThrownBy(() -> new RunRecord(
                ID,
                TENANT_ID,
                AGENT_ID,
                "principal",
                RunStatus.SUCCEEDED,
                "input",
                "output",
                "",
                STARTED_AT,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("终态必须有 finishedAt");
    }

    @Test
    void constructorRejectsFinishedAtBeforeStartedAt() {
        assertThatThrownBy(() -> new RunRecord(
                ID,
                TENANT_ID,
                AGENT_ID,
                "principal",
                RunStatus.SUCCEEDED,
                "input",
                "output",
                "",
                STARTED_AT,
                STARTED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finishedAt 不能早于 startedAt");
    }

    @Test
    void completeRejectsAlreadyTerminalRun() {
        RunRecord completed = new RunRecord(
                ID,
                TENANT_ID,
                AGENT_ID,
                "principal",
                RunStatus.SUCCEEDED,
                "input",
                "output",
                "",
                STARTED_AT,
                STARTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> completed.complete(
                RunStatus.FAILED,
                "",
                "error",
                STARTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只能完成 RUNNING 状态的运行");
    }

    @Test
    void completeRejectsRunningAsFinalStatus() {
        RunRecord running = RunRecord.create(ID, TENANT_ID, AGENT_ID, "principal", "input", STARTED_AT);

        assertThatThrownBy(() -> running.complete(RunStatus.RUNNING, "", "", STARTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finalStatus 不能为 RUNNING");
    }

    @Test
    void completeRejectsMissingFinishedAt() {
        RunRecord running = RunRecord.create(ID, TENANT_ID, AGENT_ID, "principal", "input", STARTED_AT);

        assertThatThrownBy(() -> running.complete(RunStatus.SUCCEEDED, "output", "", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("finishedAt 不能为空");
    }

    @Test
    void completeRejectsFinishedAtBeforeStartedAt() {
        RunRecord running = RunRecord.create(ID, TENANT_ID, AGENT_ID, "principal", "input", STARTED_AT);

        assertThatThrownBy(() -> running.complete(
                RunStatus.SUCCEEDED,
                "output",
                "",
                STARTED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finishedAt 不能早于 startedAt");
    }

    @Test
    void repositoryWritesExplicitlyRequireTenantScope() {
        assertThatCode(() -> RunRepository.class.getMethod("save", UUID.class, RunRecord.class))
                .doesNotThrowAnyException();
        assertThatCode(() -> ToolCallRepository.class.getMethod("saveAll", UUID.class, RunToolCallBatch.class))
                .doesNotThrowAnyException();
    }

    @Test
    void keysetUsesDescendingStartedAtAndIdAndExcludesCursorTuple() {
        RunRecord latest = RunRecord.create(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                TENANT_ID,
                AGENT_ID,
                "principal",
                "",
                STARTED_AT.plusSeconds(1));
        RunRecord sameTimestampHigherId = RunRecord.create(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                TENANT_ID,
                AGENT_ID,
                "principal",
                "",
                STARTED_AT);
        RunRecord cursor = RunRecord.create(ID, TENANT_ID, AGENT_ID, "principal", "", STARTED_AT);
        RunRecord beforeCursor = RunRecord.create(
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                TENANT_ID,
                AGENT_ID,
                "principal",
                "",
                STARTED_AT);

        List<RunRecord> runs = new ArrayList<>(List.of(cursor, beforeCursor, latest, sameTimestampHigherId));
        runs.sort(RunRepository.keysetOrder());

        assertThat(runs).containsExactly(latest, sameTimestampHigherId, cursor, beforeCursor);
        RunPageRequest pageRequest = new RunPageRequest(20, cursor.startedAt(), cursor.id());
        assertThat(RunRepository.isStrictlyBeforeCursor(beforeCursor, pageRequest)).isTrue();
        assertThat(RunRepository.isStrictlyBeforeCursor(cursor, pageRequest)).isFalse();
        assertThat(RunRepository.isStrictlyBeforeCursor(sameTimestampHigherId, pageRequest)).isFalse();
    }

    @Test
    void keysetUsesChar36LexicographicOrderAcrossUuidSignBoundary() {
        RunRecord lowerLexicographicId = RunRecord.create(
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                TENANT_ID,
                AGENT_ID,
                "principal",
                "",
                STARTED_AT);
        RunRecord higherLexicographicId = RunRecord.create(
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                TENANT_ID,
                AGENT_ID,
                "principal",
                "",
                STARTED_AT);

        List<RunRecord> runs = new ArrayList<>(List.of(lowerLexicographicId, higherLexicographicId));
        runs.sort(RunRepository.keysetOrder());

        assertThat(runs).containsExactly(higherLexicographicId, lowerLexicographicId);
    }
}
