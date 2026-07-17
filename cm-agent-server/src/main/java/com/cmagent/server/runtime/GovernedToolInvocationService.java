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
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class GovernedToolInvocationService implements ToolInvocationGateway {
    private static final Logger log = LoggerFactory.getLogger(GovernedToolInvocationService.class);
    private static final String RESOURCE_TYPE = "TOOL";
    private static final String TOOL_UNAVAILABLE = "工具不可用";
    private static final String TOOL_EXECUTION_FAILED = "工具执行失败";

    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy policy;
    private final ToolRegistry toolRegistry;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;

    public GovernedToolInvocationService(
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy policy,
            ToolRegistry toolRegistry,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    @Override
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

        ToolDefinition registeredTool = toolRegistry.find(request.toolId()).orElse(null);
        if (!isSameRegistration(tool, registeredTool)) {
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_UNAVAILABLE);
        }

        appendAudit(request, "TOOL_CALL_STARTED", "RUNNING", "工具调用已开始");
        try {
            ToolExecutionResult executionResult = toolRegistry.execute(new ToolExecutionRequest(
                    request.tenantId(), request.agentId(), request.principal(), request.runId(),
                    request.toolCallId(), request.toolId(), request.inputJson()
            ));
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

    private boolean isVisibleDefinition(ToolInvocationRequest request, ToolDefinition definition) {
        return definition.enabled()
                && request.tenantId().equals(definition.tenantId())
                && request.toolId().equals(definition.id())
                && request.toolName().equals(definition.name());
    }

    private boolean isSameRegistration(ToolDefinition tool, ToolDefinition registeredTool) {
        return registeredTool != null
                && tool.tenantId().equals(registeredTool.tenantId())
                && tool.id().equals(registeredTool.id())
                && tool.name().equals(registeredTool.name());
    }

    private void appendAudit(ToolInvocationRequest request, String eventType, String status, String message) {
        auditAppender.append(
                request.tenantId(), request.principal().principalId(), eventType, RESOURCE_TYPE,
                request.toolId().toString(), status, message
        );
    }
}
