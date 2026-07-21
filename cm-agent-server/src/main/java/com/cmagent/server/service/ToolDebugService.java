package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.security.ToolOutputSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
public class ToolDebugService {
    private final ToolDefinitionRepository toolRepository;
    private final GovernedToolExecutionService executionService;
    private final AuditAppender auditAppender;
    private final ToolOutputSanitizer sanitizer;
    private final HttpToolProperties httpToolProperties;

    public ToolDebugService(
            ToolDefinitionRepository toolRepository,
            GovernedToolExecutionService executionService,
            AuditAppender auditAppender,
            ToolOutputSanitizer sanitizer,
            HttpToolProperties httpToolProperties
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.executionService = Objects.requireNonNull(executionService, "executionService 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer 不能为空");
        this.httpToolProperties = Objects.requireNonNull(httpToolProperties, "httpToolProperties 不能为空");
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
        long startedAt = System.nanoTime();
        try {
            ToolExecutionResult result = executionService.executeWhenReady(tool, request, () ->
                    auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_STARTED", "TOOL",
                            tool.id().toString(), "RUNNING", "工具调试已开始")
            );
            long durationMillis = elapsedMillis(startedAt);
            if (result.success()) {
                auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_COMPLETED", "TOOL",
                        tool.id().toString(), "SUCCEEDED", "工具调试完成");
                return new ToolDebugResponse(true, result.statusCode(), safeOutput(result.outputSummary()), "", durationMillis);
            }
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_FAILED", "TOOL",
                    tool.id().toString(), "FAILED", "工具调试失败");
            return new ToolDebugResponse(false, result.statusCode(), "", "工具调试失败", durationMillis);
        } catch (AuditPersistenceException auditFailure) {
            throw auditFailure;
        } catch (DataAccessException dataAccessFailure) {
            throw dataAccessFailure;
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
        if (tool.type() != ToolType.HTTP && tool.type() != ToolType.LOCAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具类型不支持调试");
        }
        if (tool.riskLevel() == ToolRiskLevel.HIGH && !tool.name().equals(confirmedToolName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高风险工具确认名称不匹配");
        }
    }

    private String safeOutput(String output) {
        String sanitized = sanitizer.sanitize(output, List.of());
        return sanitizer.exceedsByteLimit(sanitized, httpToolProperties.getMaxResponseBytes())
                ? "工具输出超过安全大小限制"
                : sanitized;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
