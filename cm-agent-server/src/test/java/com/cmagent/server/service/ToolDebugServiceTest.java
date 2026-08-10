package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.cmagent.server.runtime.http.DynamicHttpToolExecutor;
import com.cmagent.server.runtime.ToolPreparationDataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ToolDebugServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock
    private ToolDefinitionRepository toolRepository;
    @Mock
    private GovernedToolExecutionService executionService;
    @Mock
    private AuditAppender auditAppender;

    private ToolDebugService service;
    private PrincipalRef principal;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        service = new ToolDebugService(toolRepository, executionService, auditAppender,
                new com.cmagent.server.security.ToolOutputSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.cmagent.server.runtime.http.HttpToolProperties());
        principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:debug"));
    }

    @Test
    /**
     * 验证或支持 {@code httpDebugUsesDebugSourceWithoutAgentOrRunAndWritesStrictAudits} 所描述的测试场景。
     */
    void httpDebugUsesDebugSourceWithoutAgentOrRunAndWritesStrictAudits() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded("结果 https://internal.example.test/secret", 200);
        });

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{\"name\":\"value\"}", null);

        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo("结果 <已脱敏URL>");
        ArgumentCaptor<ToolExecutionRequest> request = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(executionService).executeWhenReady(eq(tool), request.capture(), any());
        assertThat(request.getValue().source()).isEqualTo(ToolInvocationSource.DEBUG);
        assertThat(request.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(request.getValue().principal()).isEqualTo(principal);
        assertThat(request.getValue().agentId()).isNull();
        assertThat(request.getValue().runId()).isNull();
        assertThat(request.getValue().toolCallId()).isNotBlank();
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_STARTED", "TOOL", TOOL_ID.toString(), "RUNNING", "工具调试已开始");
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_COMPLETED", "TOOL", TOOL_ID.toString(), "SUCCEEDED", "工具调试完成");
    }

    @Test
    /**
     * 验证或支持 {@code crossTenantToolIsNotFoundWithoutExecutingOrAuditingDetails} 所描述的测试场景。
     */
    void crossTenantToolIsNotFoundWithoutExecutingOrAuditingDetails() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool(OTHER_TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "foreign")));

        assertThatThrownBy(() -> service.debug(principal, TOOL_ID, "{}", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(executionService, auditAppender);
    }

    @Test
    /**
     * 验证或支持 {@code unavailableLocalToolWritesFailureAuditWithoutStartedAudit} 所描述的测试场景。
     */
    void unavailableLocalToolWritesFailureAuditWithoutStartedAudit(CapturedOutput output) {
        ToolDefinition tool = tool(TENANT_ID, ToolType.LOCAL, ToolRiskLevel.LOW, "local-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenReturn(ToolExecutionResult.failed("工具不可用", null));

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{}", null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).isEqualTo("工具不可用");
        assertThat(response.errorCode()).isEqualTo("TOOL_UNAVAILABLE");
        assertThat(response.errorId()).isNotBlank();
        assertThat(output).contains("工具调试执行失败")
                .contains("errorId=" + response.errorId())
                .contains("errorCode=TOOL_UNAVAILABLE")
                .contains("reason=工具不可用");
        verify(auditAppender, never()).append(TENANT_ID, "admin", "TOOL_DEBUG_STARTED", "TOOL",
                TOOL_ID.toString(), "RUNNING", "工具调试已开始");
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_FAILED", "TOOL",
                TOOL_ID.toString(), "FAILED", "工具调试失败");
    }

    @Test
    /**
     * 验证或支持 {@code highRiskDebugRequiresExactServerSideToolNameConfirmation} 所描述的测试场景。
     */
    void highRiskDebugRequiresExactServerSideToolNameConfirmation() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.HIGH, "dangerous_tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));

        assertThatThrownBy(() -> service.debug(principal, TOOL_ID, "{}", "dangerous-tool"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(executionService, auditAppender);
    }

    @Test
    /**
     * 验证或支持 {@code failedStartAuditPreventsExecution} 所描述的测试场景。
     */
    void failedStartAuditPreventsExecution() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded("不应执行", 200);
        });
        AuditPersistenceException failure = new AuditPersistenceException("审计写入失败", new IllegalStateException());
        doThrow(failure).when(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_STARTED", "TOOL", TOOL_ID.toString(), "RUNNING", "工具调试已开始");

        assertThatThrownBy(() -> service.debug(principal, TOOL_ID, "{}", null)).isSameAs(failure);

        verify(executionService).executeWhenReady(eq(tool), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code failedCompletionAuditIsPropagatedAfterExecution} 所描述的测试场景。
     */
    void failedCompletionAuditIsPropagatedAfterExecution() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded("结果", 200);
        });
        AuditPersistenceException failure = new AuditPersistenceException("审计写入失败", new IllegalStateException());
        doNothing().doThrow(failure).when(auditAppender).append(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.debug(principal, TOOL_ID, "{}", null)).isSameAs(failure);

        verify(executionService).executeWhenReady(eq(tool), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code failedDebugNeverExposesToolErrorOrStackTrace} 所描述的测试场景。
     */
    void failedDebugNeverExposesToolErrorOrStackTrace(CapturedOutput output) {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.failed("java.lang.IllegalStateException: https://internal.example.test/secret", 502);
        });

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{}", null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).isEqualTo("<已脱敏异常>");
        assertThat(response.errorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(response.errorId()).isNotBlank();
        assertThat(response.errorMessage()).doesNotContain("https://", "Exception");
        assertThat(output).contains("errorId=" + response.errorId())
                .contains("reason=<已脱敏异常>")
                .doesNotContain("internal.example.test", "/secret", "IllegalStateException");
    }

    @Test
    /** 验证 HTTP 调试失败会把受控原因、状态码和同一错误编号同时返回并写入日志。 */
    void httpDebugFailureReturnsSpecificReasonAndCorrelatedLog(CapturedOutput output) {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.failed("HTTP 服务返回非成功状态", 503);
        });

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{}", null);

        assertThat(response.success()).isFalse();
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.errorMessage()).isEqualTo("HTTP 服务返回非成功状态");
        assertThat(response.errorCode()).isEqualTo("HTTP_UPSTREAM_ERROR");
        assertThat(response.errorId()).isNotBlank();
        assertThat(output).contains("errorId=" + response.errorId())
                .contains("errorCode=HTTP_UPSTREAM_ERROR")
                .contains("statusCode=503")
                .contains("reason=HTTP 服务返回非成功状态");
    }

    @Test
    /**
     * 验证或支持 {@code localDebugOutputNeverExposesJsonSecretsUrlsOrStackTrace} 所描述的测试场景。
     */
    void localDebugOutputNeverExposesJsonSecretsUrlsOrStackTrace() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.LOCAL, ToolRiskLevel.LOW, "local-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ToolExecutionResult.succeeded("""
                    {"access_token":"local-token","nested":{"client_secret":"local-secret"},
                    "url":"https://internal.example.test/private","stack":"java.lang.IllegalStateException: detail"}
                    """, 200);
        });

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{}", null);

        assertThat(response.success()).isTrue();
        assertThat(response.output()).doesNotContain("local-token", "local-secret", "https://", "IllegalStateException");
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_COMPLETED", "TOOL",
                TOOL_ID.toString(), "SUCCEEDED", "工具调试完成");
    }

    @Test
    /**
     * 验证或支持 {@code preparationPersistenceFailurePropagatesWithoutAnyDebugAudit} 所描述的测试场景。
     */
    void preparationPersistenceFailurePropagatesWithoutAnyDebugAudit() {
        ToolDefinition tool = tool(TENANT_ID, ToolType.HTTP, ToolRiskLevel.LOW, "http-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("数据库连接失败");
        when(executionService.executeWhenReady(eq(tool), any(), any()))
                .thenThrow(new ToolPreparationDataAccessException(failure));
        lenient().doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException())).when(auditAppender).append(
                any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.debug(principal, TOOL_ID, "{}", null)).isSameAs(failure);

        verify(auditAppender, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code localExecutionDataAccessFailureAfterStartedAuditReturnsControlledFailure} 所描述的测试场景。
     */
    void localExecutionDataAccessFailureAfterStartedAuditReturnsControlledFailure(CapturedOutput output) {
        ToolDefinition tool = tool(TENANT_ID, ToolType.LOCAL, ToolRiskLevel.LOW, "local-tool");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("本地执行器连接失败");
        when(executionService.executeWhenReady(eq(tool), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            throw failure;
        });

        ToolDebugResponse response = service.debug(principal, TOOL_ID, "{}", null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).isEqualTo("工具执行发生异常，请根据错误编号查看后台日志");
        assertThat(response.errorCode()).isEqualTo("TOOL_EXECUTION_EXCEPTION");
        assertThat(response.errorId()).isNotBlank();
        assertThat(output).contains("工具调试执行异常")
                .contains("errorId=" + response.errorId())
                .contains("failureType=org.springframework.dao.DataAccessResourceFailureException")
                .contains("本地执行器连接失败");
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_STARTED", "TOOL",
                TOOL_ID.toString(), "RUNNING", "工具调试已开始");
        verify(auditAppender).append(TENANT_ID, "admin", "TOOL_DEBUG_FAILED", "TOOL",
                TOOL_ID.toString(), "FAILED", "工具调试失败");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 已安装内置add通过现有调试链路执行() {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var example = new MysqlLocalExampleCatalog(objectMapper).find("add").orElseThrow();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(example.definition(), example.executor());
        HttpToolConfigRepository configs = org.mockito.Mockito.mock(HttpToolConfigRepository.class);
        DynamicHttpToolExecutor http = org.mockito.Mockito.mock(DynamicHttpToolExecutor.class);
        ToolDebugService localService = new ToolDebugService(
                toolRepository,
                new GovernedToolExecutionService(configs, http, registry),
                auditAppender,
                new com.cmagent.server.security.ToolOutputSanitizer(objectMapper),
                new com.cmagent.server.runtime.http.HttpToolProperties()
        );
        when(toolRepository.findByTenantAndId(TENANT_ID, example.definition().id()))
                .thenReturn(Optional.of(example.persistentDefinition("admin")));

        ToolDebugResponse response = localService.debug(
                principal, example.definition().id(), "{\"left\":0.1,\"right\":0.2}", null
        );

        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo("{\"sum\":0.3}");
    }

    /**
     * 构造测试工具定义。
     *
     * @param tenantId 测试租户标识
     * @param type 测试辅助方法使用的 type 参数
     * @param riskLevel 测试辅助方法使用的 riskLevel 参数
     * @param name 测试对象名称
     */
    private static ToolDefinition tool(UUID tenantId, ToolType type, ToolRiskLevel riskLevel, String name) {
        return new ToolDefinition(TOOL_ID, tenantId, name, "测试工具", type, "{}", riskLevel,
                true, type == ToolType.HTTP ? "https://example.invalid" : "", "admin", "admin");
    }
}
