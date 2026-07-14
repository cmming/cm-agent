package com.cmagent.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cmagent.core.repository.ToolCallRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunToolCallTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void toolCallRejectsNegativeDuration() {
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "echo",
                "in",
                "out",
                RunStatus.SUCCEEDED,
                true,
                -1L,
                "",
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("durationMillis 不能小于 0");
    }

    @Test
    void mixedTenantBatchIsRejectedBeforeRepositoryCanWriteAnyCall() {
        List<RunToolCall> persisted = new ArrayList<>();
        ToolCallRepository repository = new ToolCallRepository() {
            @Override
            public void saveAll(UUID tenantId, RunToolCallBatch toolCalls) {
                toolCalls.requireTenant(tenantId);
                persisted.addAll(toolCalls.toolCalls());
            }

            @Override
            public List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> repository.saveAll(TENANT_A, new RunToolCallBatch(
                TENANT_A,
                List.of(toolCall(TENANT_A), toolCall(TENANT_B)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCalls 必须全部属于 tenantId");

        assertThat(persisted).isEmpty();
    }

    @Test
    void toolCallRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new RunToolCall(
                null, TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "echo", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id 不能为空");
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), "echo", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tenantId 不能为空");
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), TENANT_A, null, UUID.randomUUID(), "echo", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("runId 不能为空");
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), TENANT_A, UUID.randomUUID(), null, "echo", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolId 不能为空");
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "echo", "", "", null,
                true, 1L, "", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status 不能为空");
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "echo", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("createdAt 不能为空");
    }

    @Test
    void toolCallRejectsBlankNameAndNormalizesBlankText() {
        assertThatThrownBy(() -> new RunToolCall(
                UUID.randomUUID(), TENANT_A, UUID.randomUUID(), UUID.randomUUID(), " ", "", "", RunStatus.SUCCEEDED,
                true, 1L, "", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolName 不能为空");

        RunToolCall toolCall = new RunToolCall(
                UUID.randomUUID(),
                TENANT_A,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "echo",
                " ",
                "\t",
                RunStatus.SUCCEEDED,
                true,
                1L,
                "\n",
                Instant.now());

        assertThat(toolCall.inputSummary()).isEmpty();
        assertThat(toolCall.outputSummary()).isEmpty();
        assertThat(toolCall.errorMessage()).isEmpty();
    }

    private static RunToolCall toolCall(UUID tenantId) {
        return new RunToolCall(
                UUID.randomUUID(),
                tenantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "echo",
                "",
                "",
                RunStatus.SUCCEEDED,
                true,
                1L,
                "",
                Instant.parse("2026-07-14T00:00:00Z"));
    }
}
