package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernedToolInvocationServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String TOOL_CALL_ID = "call-1";

    @Mock
    private ToolDefinitionRepository toolRepository;
    @Mock
    private ToolGrantRepository grantRepository;
    @Mock
    private ToolAuthorizationPolicy policy;
    @Mock
    private GovernedToolExecutionService executionService;
    @Mock
    private AuditAppender auditAppender;
    @Mock
    private SensitiveDataRedactor redactor;

    private GovernedToolInvocationService service;
    private PrincipalRef principal;
    private ToolDefinition tool;
    private ToolGrant grant;

    @BeforeEach
    void setUp() {
        service = new GovernedToolInvocationService(
                toolRepository, grantRepository, policy, executionService, auditAppender, redactor
        );
        principal = new PrincipalRef(TENANT_ID, "principal", "管理员", Set.of("tool:invoke"));
        tool = tool(TENANT_ID, "echo");
        grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
    }

    @Test
    void deniedInvocationNeverExecutesToolAndWritesAudit() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(grantRepository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).thenReturn(List.of());
        when(policy.check(principal, AGENT_ID, tool, List.of()))
                .thenReturn(AuthorizationDecision.deny("Agent 未获得工具授权 echo"));

        ToolInvocationResult result = service.invoke(request());

        assertThat(result.authorized()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Agent 未获得工具授权 echo");
        verifyNoInteractions(executionService);
        verify(auditAppender).accessDenied(principal, "TOOL", TOOL_ID.toString(),
                "tool:invoke", "Agent 未获得工具授权 echo");
    }

    @Test
    void missingDatabaseToolNeverContinuesAuthorizationOrExecution() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());

        ToolInvocationResult result = service.invoke(request());

        assertUnavailable(result);
        verifyDefinitionFailureAuditWithoutAuthorizationOrExecution();
    }

    @Test
    void crossTenantDatabaseToolNeverContinuesAuthorizationOrExecution() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID))
                .thenReturn(Optional.of(tool(OTHER_TENANT_ID, "echo")));

        ToolInvocationResult result = service.invoke(request());

        assertUnavailable(result);
        verifyDefinitionFailureAuditWithoutAuthorizationOrExecution();
    }

    @Test
    void disabledDatabaseToolNeverContinuesAuthorizationOrExecution() {
        ToolDefinition disabledTool = new ToolDefinition(
                TOOL_ID, TENANT_ID, "echo", "回显工具", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                false, "", "principal", "principal"
        );
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(disabledTool));

        ToolInvocationResult result = service.invoke(request());

        assertUnavailable(result);
        verifyDefinitionFailureAuditWithoutAuthorizationOrExecution();
    }

    @Test
    void requestedNameMismatchNeverContinuesAuthorizationOrExecution() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));

        ToolInvocationResult result = service.invoke(request("other-name"));

        assertUnavailable(result);
        verifyDefinitionFailureAuditWithoutAuthorizationOrExecution();
    }

    @Test
    void databaseDefinitionFailureAuditExceptionIsRethrownUnchangedWithoutAuthorizationOrExecution() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());
        AuditPersistenceException failure = new AuditPersistenceException(
                "审计写入失败", new IllegalStateException()
        );
        doThrow(failure).when(auditAppender).append(
                TENANT_ID, "principal", "TOOL_CALL_FAILED", "TOOL",
                TOOL_ID.toString(), "FAILED", "工具调用失败"
        );

        assertThatThrownBy(() -> service.invoke(request())).isSameAs(failure);
        verifyNoInteractions(grantRepository, policy, executionService);
    }

    @Test
    void eachInvocationReloadsAuthorizationAndRevocationPreventsSecondExecution() {
        List<ToolGrant> granted = List.of(grant);
        List<ToolGrant> revoked = List.of(new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, false));
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(grantRepository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).thenReturn(granted, revoked);
        when(policy.check(principal, AGENT_ID, tool, granted)).thenReturn(AuthorizationDecision.allow());
        when(policy.check(principal, AGENT_ID, tool, revoked))
                .thenReturn(AuthorizationDecision.deny("Agent 未获得工具授权 echo"));
        when(executionService.execute(eq(tool), any())).thenReturn(new ToolExecutionResult("工具输出", true));

        ToolInvocationResult first = service.invoke(request());
        ToolInvocationResult second = service.invoke(request());

        assertThat(first).isEqualTo(ToolInvocationResult.succeeded("工具输出"));
        assertThat(second).isEqualTo(ToolInvocationResult.denied("Agent 未获得工具授权 echo"));
        verify(toolRepository, times(2)).findByTenantAndId(TENANT_ID, TOOL_ID);
        verify(grantRepository, times(2)).listByTenantAndAgent(TENANT_ID, AGENT_ID);
        verify(policy).check(principal, AGENT_ID, tool, granted);
        verify(policy).check(principal, AGENT_ID, tool, revoked);
        verify(executionService, times(1)).execute(eq(tool), any());
        verify(auditAppender).accessDenied(
                principal, "TOOL", TOOL_ID.toString(), "tool:invoke", "Agent 未获得工具授权 echo"
        );
    }

    @Test
    void successfulInvocationUsesCompleteContextAndWritesAuditsInGovernedOrder() {
        arrangeAllowedTool();
        when(executionService.execute(eq(tool), any())).thenReturn(new ToolExecutionResult("工具输出", true));

        ToolInvocationResult result = service.invoke(request());

        assertThat(result).isEqualTo(ToolInvocationResult.succeeded("工具输出"));
        ArgumentCaptor<ToolExecutionRequest> executionRequest = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        InOrder order = inOrder(toolRepository, grantRepository, policy, executionService, auditAppender);
        order.verify(toolRepository).findByTenantAndId(TENANT_ID, TOOL_ID);
        order.verify(grantRepository).listByTenantAndAgent(TENANT_ID, AGENT_ID);
        order.verify(policy).check(principal, AGENT_ID, tool, List.of(grant));
        order.verify(auditAppender).append(TENANT_ID, "principal", "TOOL_CALL_STARTED",
                "TOOL", TOOL_ID.toString(), "RUNNING", "工具调用已开始");
        order.verify(executionService).execute(eq(tool), executionRequest.capture());
        order.verify(auditAppender).append(TENANT_ID, "principal", "TOOL_CALL_COMPLETED",
                "TOOL", TOOL_ID.toString(), "SUCCEEDED", "工具调用完成");
        assertThat(executionRequest.getValue()).isEqualTo(new ToolExecutionRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, TOOL_CALL_ID, TOOL_ID, "{\"text\":\"hello\"}"
        ));
        assertThat(executionRequest.getValue().source()).isEqualTo(ToolInvocationSource.AGENT);
    }

    @Test
    void executorReturnedFailureWritesFixedFailureAuditAndReturnsControlledError() {
        arrangeAllowedTool();
        when(executionService.execute(eq(tool), any())).thenReturn(new ToolExecutionResult("password=tool-secret", false));

        ToolInvocationResult result = service.invoke(request());

        assertThat(result).isEqualTo(ToolInvocationResult.failed("工具执行失败"));
        verify(auditAppender).append(TENANT_ID, "principal", "TOOL_CALL_FAILED",
                "TOOL", TOOL_ID.toString(), "FAILED", "工具调用失败");
    }

    @Test
    void executorExceptionIsRedactedBeforeLoggingAndWritesFixedFailureAudit() {
        arrangeAllowedTool();
        IllegalStateException failure = new IllegalStateException("password=tool-secret");
        when(executionService.execute(eq(tool), any())).thenThrow(failure);
        when(redactor.redact(failure.getMessage())).thenReturn("password=<已脱敏>");

        ToolInvocationResult result = service.invoke(request());

        assertThat(result).isEqualTo(ToolInvocationResult.failed("工具执行失败"));
        verify(redactor).redact(failure.getMessage());
        verify(auditAppender).append(TENANT_ID, "principal", "TOOL_CALL_FAILED",
                "TOOL", TOOL_ID.toString(), "FAILED", "工具调用失败");
    }

    @Test
    void auditFailureBeforeInvocationPreventsSideEffect() {
        arrangeAllowedTool();
        AuditPersistenceException failure = new AuditPersistenceException(
                "审计写入失败", new IllegalStateException()
        );
        doThrow(failure).when(auditAppender).append(
                any(), any(), eq("TOOL_CALL_STARTED"), any(), any(), any(), any()
        );

        assertThatThrownBy(() -> service.invoke(request())).isSameAs(failure);
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void completionAuditFailureIsRethrownUnchanged() {
        arrangeAllowedTool();
        when(executionService.execute(eq(tool), any())).thenReturn(new ToolExecutionResult("工具输出", true));
        AuditPersistenceException failure = new AuditPersistenceException(
                "审计写入失败", new IllegalStateException()
        );
        doNothing().doThrow(failure).when(auditAppender).append(
                any(), any(), any(), any(), any(), any(), any()
        );

        assertThatThrownBy(() -> service.invoke(request())).isSameAs(failure);
    }

    private void arrangeAuthorizedTool() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(grantRepository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).thenReturn(List.of(grant));
        when(policy.check(principal, AGENT_ID, tool, List.of(grant))).thenReturn(AuthorizationDecision.allow());
    }

    private void arrangeAllowedTool() {
        arrangeAuthorizedTool();
    }

    private ToolInvocationRequest request() {
        return request("echo");
    }

    private ToolInvocationRequest request(String toolName) {
        return new ToolInvocationRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, TOOL_CALL_ID, TOOL_ID, toolName, "{\"text\":\"hello\"}"
        );
    }

    private static ToolDefinition tool(UUID tenantId, String name) {
        return tool(TOOL_ID, tenantId, name);
    }

    private static ToolDefinition tool(UUID toolId, UUID tenantId, String name) {
        return new ToolDefinition(
                toolId, tenantId, name, "回显工具", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                true, "", "principal", "principal"
        );
    }

    private void assertUnavailable(ToolInvocationResult result) {
        assertThat(result).isEqualTo(ToolInvocationResult.failed("工具不可用"));
    }

    private void verifyDefinitionFailureAuditWithoutAuthorizationOrExecution() {
        verify(auditAppender).append(TENANT_ID, "principal", "TOOL_CALL_FAILED",
                "TOOL", TOOL_ID.toString(), "FAILED", "工具调用失败");
        verifyNoInteractions(grantRepository, policy, executionService);
    }
}
