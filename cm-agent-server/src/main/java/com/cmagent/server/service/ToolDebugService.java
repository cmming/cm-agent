package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class ToolDebugService {
    private final ToolDefinitionRepository toolRepository;
    private final GovernedToolExecutionService executionService;
    private final ToolRegistry registry;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;

    public ToolDebugService(
            ToolDefinitionRepository toolRepository,
            GovernedToolExecutionService executionService,
            ToolRegistry registry,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.executionService = Objects.requireNonNull(executionService, "executionService 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    public ToolDebugResponse debug(PrincipalRef principal, UUID toolId, String inputJson, String confirmedToolName) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(inputJson, "inputJson 不能为空");
        ToolDefinition tool = toolRepository.findByTenantAndId(principal.tenantId(), toolId)
                .filter(candidate -> isVisible(principal, toolId, candidate))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        validateDebugScope(tool, confirmedToolName);

        String toolCallId = UUID.randomUUID().toString();
        ToolExecutionRequest request = new ToolExecutionRequest(
                principal.tenantId(), null, principal, null, toolCallId, tool.id(), inputJson, ToolInvocationSource.DEBUG
        );
        auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_STARTED", "TOOL",
                tool.id().toString(), "RUNNING", "工具调试已开始");
        long startedAt = System.nanoTime();
        try {
            ToolExecutionResult result = executionService.execute(tool, request);
            long durationMillis = elapsedMillis(startedAt);
            if (result.success()) {
                auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_COMPLETED", "TOOL",
                        tool.id().toString(), "SUCCEEDED", "工具调试完成");
                return new ToolDebugResponse(true, result.statusCode(), redactor.redact(result.outputSummary()), "", durationMillis);
            }
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_FAILED", "TOOL",
                    tool.id().toString(), "FAILED", "工具调试失败");
            return new ToolDebugResponse(false, result.statusCode(), "", "工具调试失败", durationMillis);
        } catch (AuditPersistenceException auditFailure) {
            throw auditFailure;
        } catch (RuntimeException executionFailure) {
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_FAILED", "TOOL",
                    tool.id().toString(), "FAILED", "工具调试失败");
            return new ToolDebugResponse(false, null, "", "工具调试失败", elapsedMillis(startedAt));
        }
    }

    private boolean isVisible(PrincipalRef principal, UUID toolId, ToolDefinition tool) {
        return tool.enabled() && principal.tenantId().equals(tool.tenantId()) && toolId.equals(tool.id());
    }

    private void validateDebugScope(ToolDefinition tool, String confirmedToolName) {
        if (tool.type() == ToolType.LOCAL && !isSameRegistration(tool)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本地工具未注册或定义不一致");
        }
        if (tool.type() != ToolType.HTTP && tool.type() != ToolType.LOCAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具类型不支持调试");
        }
        if (tool.riskLevel() == ToolRiskLevel.HIGH && !tool.name().equals(confirmedToolName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高风险工具确认名称不匹配");
        }
    }

    private boolean isSameRegistration(ToolDefinition tool) {
        return registry.snapshot(tool.id())
                .map(ToolRegistry.ToolRegistrationSnapshot::definition)
                .map(registered -> tool.tenantId().equals(registered.tenantId())
                        && tool.id().equals(registered.id())
                        && tool.name().equals(registered.name()))
                .orElse(false);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
