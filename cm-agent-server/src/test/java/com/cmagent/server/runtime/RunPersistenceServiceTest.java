package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunPersistenceServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private RunRepository runRepository;
    @Mock
    private ToolCallRepository toolCallRepository;
    @Mock
    private AuditAppender auditAppender;

    private RunPersistenceService service;
    private PrincipalRef principal;
    private RunRecord runningRun;
    private ToolDefinition tool;

    @BeforeEach
    void setUp() {
        service = new RunPersistenceService(
                runRepository, toolCallRepository, auditAppender, new SensitiveDataRedactor(), null
        );
        principal = new PrincipalRef(TENANT_ID, "principal", "管理员", Set.of("run:write"));
        runningRun = RunRecord.create(RUN_ID, TENANT_ID, AGENT_ID, "principal", "input", STARTED_AT);
        tool = new ToolDefinition(
                TOOL_ID, TENANT_ID, "echo", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                true, "", "principal", "principal"
        );
    }

    @Test
    void completionPreparationFailureClosesRunningRun() {
        ToolCallRecord overflowingToolCall = new ToolCallRecord(
                TOOL_ID, "echo", "password=secret", "", RunStatus.SUCCEEDED,
                Duration.ofSeconds(Long.MAX_VALUE), true, ""
        );
        RunRecord failedRun = runningRun.complete(
                RunStatus.FAILED, "", "Agent 运行失败", STARTED_AT
        );
        when(runRepository.complete(
                eq(TENANT_ID), eq(RUN_ID), eq(RunStatus.FAILED), eq(""), eq("Agent 运行失败"), any(Instant.class)
        )).thenReturn(failedRun);

        assertThatThrownBy(() -> service.complete(
                principal,
                runningRun,
                new AgentRunResult(RUN_ID, RunStatus.SUCCEEDED, "", List.of(overflowingToolCall), STARTED_AT, STARTED_AT, ""),
                List.of(tool)
        )).isInstanceOf(ArithmeticException.class);

        verify(runRepository).complete(
                eq(TENANT_ID), eq(RUN_ID), eq(RunStatus.FAILED), eq(""), eq("Agent 运行失败"), any(Instant.class)
        );
    }

    @Test
    void readApisRedactPersistedRuntimeData() {
        RunRecord storedRun = new RunRecord(
                RUN_ID, TENANT_ID, AGENT_ID, "principal", RunStatus.SUCCEEDED,
                "password=run-secret", "apiKey=run-key", "Bearer run-token", STARTED_AT, STARTED_AT
        );
        RunToolCall storedToolCall = new RunToolCall(
                UUID.fromString("50000000-0000-0000-0000-000000000001"), TENANT_ID, RUN_ID, TOOL_ID, "echo",
                "password=tool-secret", "apiKey=tool-key", RunStatus.SUCCEEDED, true, 1L,
                "Bearer tool-token", STARTED_AT
        );
        when(runRepository.findByTenantAndAgentAndId(TENANT_ID, AGENT_ID, RUN_ID)).thenReturn(Optional.of(storedRun));
        when(toolCallRepository.listByTenantAndRun(TENANT_ID, RUN_ID)).thenReturn(List.of(storedToolCall));
        when(runRepository.listByTenantAndAgent(eq(TENANT_ID), eq(AGENT_ID), any())).thenReturn(List.of(storedRun));

        RunPersistenceService.RunDetail detail = service.findDetail(TENANT_ID, AGENT_ID, RUN_ID);
        List<RunRecord> listed = service.list(TENANT_ID, AGENT_ID, new com.cmagent.core.domain.RunPageRequest(10, null, null));

        assertThat(detail.run().input()).isEqualTo("password=<已脱敏>");
        assertThat(detail.run().output()).isEqualTo("apiKey=<已脱敏>");
        assertThat(detail.run().errorMessage()).isEqualTo("Bearer <已脱敏>");
        assertThat(detail.toolCalls().getFirst().inputSummary()).isEqualTo("password=<已脱敏>");
        assertThat(detail.toolCalls().getFirst().outputSummary()).isEqualTo("apiKey=<已脱敏>");
        assertThat(detail.toolCalls().getFirst().errorMessage()).isEqualTo("Bearer <已脱敏>");
        assertThat(listed.getFirst().input()).isEqualTo("password=<已脱敏>");
        assertThat(listed.getFirst().output()).isEqualTo("apiKey=<已脱敏>");
    }
}
