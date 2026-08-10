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
import com.cmagent.server.runtime.ToolPreparationDataAccessException;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.security.ToolOutputSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
/** 提供受权限保护的工具调试能力，统一限制风险工具和输出内容。 */
public class ToolDebugService {
    private static final Logger log = LoggerFactory.getLogger(ToolDebugService.class);
    private static final String TOOL_EXECUTION_EXCEPTION = "TOOL_EXECUTION_EXCEPTION";

    private final ToolDefinitionRepository toolRepository;
    private final GovernedToolExecutionService executionService;
    private final AuditAppender auditAppender;
    private final ToolOutputSanitizer sanitizer;
    private final HttpToolProperties httpToolProperties;
    /**
     * 创建 {@code ToolDebugService} 实例并保存其运行所需依赖。
     *
     * @param toolRepository 工具定义仓储。
     * @param executionService 负责当前业务流程的服务。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param sanitizer 负责清理工具输出的安全组件。
     * @param httpToolProperties HTTP 工具调试确认和超时限制配置
     */
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

    /**
     * 在调试权限和风险确认均通过后执行工具。
     *
     * @param principal         当前认证主体
     * @param toolId            工具标识
     * @param inputJson         工具输入 JSON
     * @param confirmedToolName 高风险工具的二次确认名称
     * @return 脱敏后的调试响应
     * @throws ResponseStatusException 工具不可见、未授权、确认失败或输入无效时抛出
     */
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
                return new ToolDebugResponse(
                        true, result.statusCode(), safeOutput(result.outputSummary()), "", durationMillis, "", ""
                );
            }
            FailureDetails failure = failureDetails(result);
            log.error(
                    "工具调试执行失败。errorId={}, tenantId={}, principalId={}, toolId={}, toolCallId={}, "
                            + "toolType={}, errorCode={}, statusCode={}, reason={}",
                    toolCallId, principal.tenantId(), principal.principalId(), tool.id(), toolCallId,
                    tool.type(), failure.code(), result.statusCode(), failure.message()
            );
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_FAILED", "TOOL",
                    tool.id().toString(), "FAILED", "工具调试失败");
            return new ToolDebugResponse(
                    false, result.statusCode(), "", failure.message(), durationMillis, failure.code(), toolCallId
            );
        } catch (AuditPersistenceException auditFailure) {
            throw auditFailure;
        } catch (ToolPreparationDataAccessException preparationFailure) {
            throw preparationFailure.dataAccessException();
        } catch (RuntimeException executionFailure) {
            logExecutionException(principal, tool, toolCallId, executionFailure);
            auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_DEBUG_FAILED", "TOOL",
                    tool.id().toString(), "FAILED", "工具调试失败");
            return new ToolDebugResponse(
                    false, null, "", "工具执行发生异常，请根据错误编号查看后台日志",
                    elapsedMillis(startedAt), TOOL_EXECUTION_EXCEPTION, toolCallId
            );
        }
    }

    /**
     * 将执行器返回的受控原因转换为前端可稳定识别的错误码和脱敏说明。
     *
     * @param result 工具执行失败结果
     * @return 可安全返回和记录的失败详情
     */
    private FailureDetails failureDetails(ToolExecutionResult result) {
        String message = safeOutput(result.errorMessage());
        if (message.isBlank()) {
            message = "工具执行失败，请根据错误编号查看后台日志";
        }
        return new FailureDetails(failureCode(result.errorMessage()), message);
    }

    /**
     * 根据执行器的稳定失败原因生成便于前端处理和后台检索的错误码。
     *
     * @param errorMessage 执行器返回的受控失败原因
     * @return 稳定错误码
     */
    private String failureCode(String errorMessage) {
        return switch (errorMessage == null ? "" : errorMessage) {
            case "工具不可用" -> "TOOL_UNAVAILABLE";
            case "HTTP 工具未启用" -> "HTTP_TOOL_DISABLED";
            case "HTTP 超时配置不允许" -> "HTTP_TIMEOUT_CONFIGURATION_INVALID";
            case "HTTP 工具调用超时", "HTTP 请求超时" -> "HTTP_TIMEOUT";
            case "HTTP 请求中断" -> "HTTP_REQUEST_INTERRUPTED";
            case "HTTP Secret 不可用" -> "HTTP_SECRET_UNAVAILABLE";
            case "HTTP 工具输入无效" -> "HTTP_INPUT_INVALID";
            case "HTTP 请求头配置不安全" -> "HTTP_HEADER_REJECTED";
            case "HTTP 请求地址无效" -> "HTTP_URL_INVALID";
            case "HTTP 重定向次数超限", "HTTP 重定向响应头无效", "HTTP 重定向跨源被拒绝", "HTTP 重定向地址无效" ->
                    "HTTP_REDIRECT_REJECTED";
            case "HTTP 服务返回非成功状态" -> "HTTP_UPSTREAM_ERROR";
            case "HTTP 响应编码不受支持", "HTTP 响应头不安全", "HTTP 响应类型不受支持",
                    "HTTP 响应超过大小限制", "HTTP JSON 响应无效" -> "HTTP_RESPONSE_INVALID";
            case "HTTP 目标地址不允许" -> "HTTP_TARGET_REJECTED";
            case "HTTP TLS 连接失败" -> "HTTP_TLS_ERROR";
            case "HTTP 连接失败" -> "HTTP_CONNECTION_ERROR";
            default -> "TOOL_EXECUTION_FAILED";
        };
    }

    /**
     * 记录不向前端暴露内部细节的执行异常，并保留脱敏堆栈供后台定位。
     *
     * @param principal 当前认证主体
     * @param tool 当前工具定义
     * @param errorId 前端与日志共享的错误编号
     * @param failure 执行器抛出的异常
     */
    private void logExecutionException(
            PrincipalRef principal,
            ToolDefinition tool,
            String errorId,
            RuntimeException failure
    ) {
        String safeReason = safeOutput(failure.getMessage());
        RuntimeException diagnostic = new RuntimeException(
                safeReason.isBlank() ? "执行器未提供异常说明" : safeReason
        );
        diagnostic.setStackTrace(failure.getStackTrace());
        log.error(
                "工具调试执行异常。errorId={}, tenantId={}, principalId={}, toolId={}, toolCallId={}, "
                        + "toolType={}, errorCode={}, failureType={}",
                errorId, principal.tenantId(), principal.principalId(), tool.id(), errorId,
                tool.type(), TOOL_EXECUTION_EXCEPTION, failure.getClass().getName(), diagnostic
        );
    }

    /**
     * 判断方法名所描述的业务条件是否成立。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     * @param tool 当前处理的工具定义。
     */
    private boolean isVisible(PrincipalRef principal, UUID toolId, ToolDefinition tool) {
        return tool.enabled() && principal.tenantId().equals(tool.tenantId()) && toolId.equals(tool.id());
    }

    /**
     * 校验调试请求的工具类型、风险确认和权限范围。
     *
     * @param tool 当前处理的工具定义。
     * @param confirmedToolName 用户二次确认的高风险工具名称
     */
    private void validateDebugScope(ToolDefinition tool, String confirmedToolName) {
        if (tool.type() != ToolType.HTTP && tool.type() != ToolType.LOCAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具类型不支持调试");
        }
        if (tool.riskLevel() == ToolRiskLevel.HIGH && !tool.name().equals(confirmedToolName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高风险工具确认名称不匹配");
        }
    }

    /**
     * 对工具输出脱敏并限制可返回内容。
     *
     * @param output 本次处理产生或待处理的输出内容。
     */
    private String safeOutput(String output) {
        String sanitized = sanitizer.sanitize(output, List.of());
        return sanitizer.exceedsByteLimit(sanitized, httpToolProperties.getMaxResponseBytes())
                ? "工具输出超过安全大小限制"
                : sanitized;
    }

    /**
     * 计算从开始时刻到当前时刻的非负耗时毫秒数。
     *
     * @param startedAt 开始计算耗时的时间点
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** 表示已经完成脱敏并可返回前端的工具失败详情。 */
    private record FailureDetails(String code, String message) {
    }
}
