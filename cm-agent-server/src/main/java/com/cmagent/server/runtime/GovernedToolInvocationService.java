package com.cmagent.server.runtime;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
/** 面向 AgentScope 的工具调用网关，确保每次调用都重新经过治理链路。 */
public class GovernedToolInvocationService implements ToolInvocationGateway {
    private static final Logger log = LoggerFactory.getLogger(GovernedToolInvocationService.class);
    private static final String RESOURCE_TYPE = "TOOL";
    private static final String TOOL_UNAVAILABLE = "工具不可用";
    private static final String TOOL_EXECUTION_FAILED = "工具执行失败";

    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy policy;
    private final GovernedToolExecutionService executionService;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;
    /**
     * GovernedToolInvocationService：处理该类内部的业务逻辑或辅助计算。
     */
    public GovernedToolInvocationService(
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy policy,
            GovernedToolExecutionService executionService,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.executionService = Objects.requireNonNull(executionService, "executionService 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    @Override
    /**
     * 接收 AgentScope 的工具调用，并在每次调用时重新执行租户、权限和工具状态校验。
     *
     * @param request 包含主体、工具和输入的调用请求
     * @return 受治理的工具调用结果
     * @throws RuntimeException 治理失败、工具执行失败或审计失败时抛出
     */
    public ToolInvocationResult invoke(ToolInvocationRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        ToolDefinition tool = toolRepository.findByTenantAndId(request.tenantId(), request.toolId())
                .filter(definition -> isVisibleDefinition(request, definition))
                .orElse(null);
        if (tool == null) {
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_UNAVAILABLE);
        }

        List<ToolGrant> grants = grantRepository.listByTenantAndAgent(request.tenantId(), request.agentId());
        AuthorizationDecision decision = policy.check(request.principal(), request.agentId(), tool, grants);
        if (!decision.allowed()) {
            auditAppender.accessDenied(
                    request.principal(), RESOURCE_TYPE, request.toolId().toString(), "tool:invoke", decision.reason()
            );
            return ToolInvocationResult.denied(decision.reason());
        }

        ToolExecutionRequest executionRequest = new ToolExecutionRequest(
                request.tenantId(), request.agentId(), request.principal(), request.runId(),
                request.toolCallId(), request.toolId(), request.inputJson()
        );
        GovernedToolExecutionService.PreparedToolExecution prepared = executionService.prepare(tool, executionRequest);
        if (!prepared.ready()) {
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_UNAVAILABLE);
        }
        appendAudit(request, "TOOL_CALL_STARTED", "RUNNING", "工具调用已开始");
        try {
            ToolExecutionResult executionResult = prepared.execute();
            if (executionResult.success()) {
                appendAudit(request, "TOOL_CALL_COMPLETED", "SUCCEEDED", "工具调用完成");
                return ToolInvocationResult.succeeded(executionResult.outputSummary());
            }
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_EXECUTION_FAILED);
        } catch (AuditPersistenceException auditFailure) {
            throw auditFailure;
        } catch (RuntimeException executionFailure) {
            log.warn("工具执行失败。toolId={}, reason={}",
                    request.toolId(), redactor.redact(executionFailure.getMessage()));
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_EXECUTION_FAILED);
        }
    }

    /**
     * isVisibleDefinition：判断当前条件是否成立。
     */
    private boolean isVisibleDefinition(ToolInvocationRequest request, ToolDefinition definition) {
        return definition.enabled()
                && request.tenantId().equals(definition.tenantId())
                && request.toolId().equals(definition.id())
                && request.toolName().equals(definition.name());
    }

    /**
     * appendAudit：追加处理结果或审计记录。
     */
    private void appendAudit(ToolInvocationRequest request, String eventType, String status, String message) {
        auditAppender.append(
                request.tenantId(), request.principal().principalId(), eventType, RESOURCE_TYPE,
                request.toolId().toString(), status, message
        );
    }
}
