package com.cmagent.server.diagnostic;

import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.security.ToolOutputSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 统一记录可关联、可检索且不包含敏感内容的失败诊断日志。 */
@Component
public class ErrorDiagnosticLogger {
    private static final Logger log = LoggerFactory.getLogger(ErrorDiagnosticLogger.class);
    private static final Pattern SQL_STATEMENT = Pattern.compile("(?is)\\b(?:select|insert|update|delete|merge|alter|drop|create)\\b.*");

    private final SensitiveDataRedactor redactor;
    private final ToolOutputSanitizer sanitizer;

    public ErrorDiagnosticLogger(SensitiveDataRedactor redactor, ToolOutputSanitizer sanitizer) {
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer 不能为空");
    }

    /**
     * 记录失败原因和原始异常堆栈位置；异常消息会先脱敏，避免日志泄露请求内容或凭据。
     *
     * @param context 可检索的上下文
     * @param failure 原始失败，用于保留堆栈位置
     */
    public void error(DiagnosticContext context, Throwable failure) {
        error(context, failure, null);
    }

    /**
     * 记录失败原因、原始异常堆栈位置和可供排查的上游返回内容。
     *
     * <p>上游返回内容仅写入服务端日志，且会经过与普通失败原因一致的脱敏处理；调用方不得将其
     * 透传到 API 响应、审计事件或其他面向用户的输出。</p>
     *
     * @param context 可检索的上下文
     * @param failure 原始失败，用于保留堆栈位置
     * @param upstreamResponse 上游响应正文；没有响应正文时传入 {@code null}
     */
    public void error(DiagnosticContext context, Throwable failure, String upstreamResponse) {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(failure, "failure 不能为空");
        String safeReason = SQL_STATEMENT.matcher(sanitizer.sanitize(redactor.redact(failure.getMessage()), List.of()))
                .replaceAll("<已脱敏SQL>");
        String safeUpstreamResponse = safeReason(upstreamResponse);
        RuntimeException safeFailure = new RuntimeException(safeReason.isBlank() ? "未提供异常消息" : safeReason);
        safeFailure.setStackTrace(failure.getStackTrace());
        log.error(
                "操作失败。errorId={}, boundary={}, errorCode={}, tenantId={}, principalId={}, agentId={}, runId={}, toolId={}, toolCallId={}, source={}, exceptionType={}, reason={}, upstreamResponse={}",
                context.errorId(), context.boundary(), context.errorCode(), context.tenantId(), context.principalId(),
                context.agentId(), context.runId(), context.toolId(), context.toolCallId(), context.source(),
                failure.getClass().getName(), safeReason, safeUpstreamResponse, safeFailure
        );
    }

    /**
     * 记录已被执行器转换为受控结果的失败；此类失败没有可用异常堆栈。
     *
     * @param context 可检索的上下文
     * @param reason 已控制的失败说明，仍会再次脱敏
     */
    public void error(DiagnosticContext context, String reason) {
        Objects.requireNonNull(context, "context 不能为空");
        String safeReason = safeReason(reason);
        log.error(
                "操作失败。errorId={}, boundary={}, errorCode={}, tenantId={}, principalId={}, agentId={}, runId={}, toolId={}, toolCallId={}, source={}, exceptionType=CONTROLLED_FAILURE, reason={}",
                context.errorId(), context.boundary(), context.errorCode(), context.tenantId(), context.principalId(),
                context.agentId(), context.runId(), context.toolId(), context.toolCallId(), context.source(), safeReason
        );
    }

    private String safeReason(String value) {
        return SQL_STATEMENT.matcher(sanitizer.sanitize(redactor.redact(value), List.of())).replaceAll("<已脱敏SQL>");
    }

    /** 失败日志所需的稳定检索字段。 */
    public record DiagnosticContext(
            String errorId,
            String boundary,
            String errorCode,
            String tenantId,
            String principalId,
            String agentId,
            String runId,
            String toolId,
            String toolCallId,
            String source
    ) {
        public DiagnosticContext {
            errorId = required(errorId, "errorId");
            boundary = required(boundary, "boundary");
            errorCode = required(errorCode, "errorCode");
            tenantId = valueOrDash(tenantId);
            principalId = valueOrDash(principalId);
            agentId = valueOrDash(agentId);
            runId = valueOrDash(runId);
            toolId = valueOrDash(toolId);
            toolCallId = valueOrDash(toolCallId);
            source = valueOrDash(source);
        }

        public static DiagnosticContext api(String errorId, String errorCode, String source) {
            return new DiagnosticContext(errorId, "REST_API", errorCode, "-", "-", "-", "-", "-", "-", source);
        }

        private static String required(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " 不能为空");
            }
            return value;
        }

        private static String valueOrDash(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }
    }
}
