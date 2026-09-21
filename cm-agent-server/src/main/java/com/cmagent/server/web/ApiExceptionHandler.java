package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiErrorResponse;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.service.ModelCatalogDiscoveryException;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

@RestControllerAdvice
/** 将常见业务异常转换为稳定、脱敏的 JSON 错误响应。 */
public class ApiExceptionHandler {
    private final SensitiveDataRedactor redactor;
    private final ErrorDiagnosticLogger diagnosticLogger;
    /**
     * 创建 {@code ApiExceptionHandler} 实例并保存其运行所需依赖。
     */
    public ApiExceptionHandler() {
        this(new SensitiveDataRedactor(), new ErrorDiagnosticLogger(
                new SensitiveDataRedactor(),
                new ToolOutputSanitizer(new ObjectMapper())
        ));
    }

    @Autowired
    /**
     * 创建 {@code ApiExceptionHandler} 实例并保存其运行所需依赖。
     *
     * @param redactor 负责清理敏感文本的脱敏器
     * @param diagnosticLogger 负责记录脱敏的失败诊断日志
     */
    public ApiExceptionHandler(SensitiveDataRedactor redactor, ErrorDiagnosticLogger diagnosticLogger) {
        this.redactor = redactor;
        this.diagnosticLogger = diagnosticLogger;
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            TypeMismatchException.class,
            ConversionFailedException.class,
            IllegalArgumentException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    /**
     * 将参数绑定、类型转换和校验异常统一转换为参数错误响应。
     *
     * @param ignored 仅用于满足回调签名、不参与业务判断的参数。
     */
    public ResponseEntity<ApiErrorResponse> validationFailure(Exception ignored, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,
                approvalDecisionRequest(request) ? ApiErrorCode.TOOL_APPROVAL_INVALID_DECISION : ApiErrorCode.VALIDATION_FAILED,
                "请求参数不合法", request);
    }

    @ExceptionHandler(DataAccessException.class)
    /**
     * 将普通数据访问异常转换为数据服务暂不可用响应。
     *
     * @param ignored 仅用于满足回调签名、不参与业务判断的参数。
     */
    public ResponseEntity<ApiErrorResponse> persistenceFailure(DataAccessException failure, HttpServletRequest request) {
        return failedResponse(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.PERSISTENCE_UNAVAILABLE,
                "数据服务暂不可用", failure, request);
    }

    @ExceptionHandler(AuditPersistenceException.class)
    /**
     * 将审计持久化异常转换为审计服务暂不可用响应。
     *
     * @param ignored 仅用于满足回调签名、不参与业务判断的参数。
     */
    public ResponseEntity<ApiErrorResponse> auditPersistenceFailure(AuditPersistenceException failure, HttpServletRequest request) {
        return failedResponse(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.AUDIT_UNAVAILABLE,
                "审计服务暂不可用", failure, request);
    }

    /**
     * 将技能领域的受控失败映射为稳定 HTTP 状态，并保留异常在跨层链路中承诺的错误编号。
     *
     * @param failure 已脱敏的技能失败
     * @return 技能专属错误响应
     */
    @ExceptionHandler(SkillAccessException.class)
    public ResponseEntity<ApiErrorResponse> skillFailure(SkillAccessException failure) {
        HttpStatus status = switch (failure.code()) {
            case SKILL_PACKAGE_INVALID, SKILL_RESOURCE_UNSUPPORTED -> HttpStatus.BAD_REQUEST;
            case SKILL_PACKAGE_TOO_LARGE, SKILL_LOAD_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case SKILL_CONFLICT -> HttpStatus.CONFLICT;
            case SKILL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SKILL_ACCESS_REVOKED -> HttpStatus.GONE;
            case SKILL_FEATURE_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case SKILL_SNAPSHOT_UNAVAILABLE, SKILL_LOAD_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code(), failure.safeMessage(), failure.errorId());
    }

    /** 技能 multipart 缺失或超过容器限制时仍返回 JSON 和稳定技能错误码。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiErrorResponse> skillMultipartFailure(Exception failure, HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/skills")) {
            return validationFailure(failure, request);
        }
        boolean tooLarge = failure instanceof MaxUploadSizeExceededException;
        return response(tooLarge ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST,
                tooLarge ? ApiErrorCode.SKILL_PACKAGE_TOO_LARGE : ApiErrorCode.SKILL_PACKAGE_INVALID,
                tooLarge ? "技能 ZIP 超过上传限制" : "缺少技能 ZIP 文件", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    /**
     * 将业务层 HTTP 状态异常映射为稳定、脱敏的 API 错误码和消息。
     *
     * @param exception 当前捕获的异常，用于转换或记录失败信息。
     */
    public ResponseEntity<ApiErrorResponse> statusFailure(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return switch (status) {
            // ResponseStatusException 的 BAD_REQUEST 仅由业务层受控文案构造；
            // 保留其原因可让控制台区分停用模型、工具类型等可操作失败，同时仍经过脱敏。
            case BAD_REQUEST -> response(
                    status,
                    approvalDecisionRequest(request) ? ApiErrorCode.TOOL_APPROVAL_INVALID_DECISION : ApiErrorCode.VALIDATION_FAILED,
                    exception.getReason() == null || exception.getReason().isBlank()
                            ? "请求参数不合法"
                            : exception.getReason(),
                    request
            );
            case UNAUTHORIZED -> response(status, ApiErrorCode.UNAUTHORIZED, "未登录或令牌无效", request);
            case FORBIDDEN -> response(status, ApiErrorCode.FORBIDDEN, "没有权限执行该操作", request);
            case CONFLICT -> response(
                    status,
                    approvalRequest(request) ? ApiErrorCode.TOOL_APPROVAL_CONFLICT : ApiErrorCode.VALIDATION_FAILED,
                    exception.getReason() == null || exception.getReason().isBlank()
                            ? "请求资源冲突"
                            : exception.getReason(),
                    request
            );
            // 历史集合没有单个审批资源；此处 404 表示会话或 Agent 不可见，而不是历史记录为空。
            case NOT_FOUND -> request.getRequestURI().endsWith("/approvals/history")
                    ? response(status, ApiErrorCode.CONVERSATION_NOT_FOUND, "会话或 Agent 不存在", request)
                    : approvalRequest(request)
                    ? response(status, ApiErrorCode.TOOL_APPROVAL_NOT_FOUND, "审批请求不存在", request)
                    : request.getRequestURI().contains("/conversations")
                        ? response(status, ApiErrorCode.CONVERSATION_NOT_FOUND, "会话或 Agent 不存在", request)
                        : response(status, ApiErrorCode.RUNTIME_ERROR, "请求资源不存在", request);
            case GONE -> approvalRequest(request)
                    ? response(status, ApiErrorCode.TOOL_APPROVAL_EXPIRED, "审批请求已过期", request)
                    : response(status, ApiErrorCode.VALIDATION_FAILED, "请求资源已失效", request);
            default -> failedResponse(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "服务内部错误", exception, request);
        };
    }

    /**
     * 将模型目录发现的受控失败转换为可操作响应，并使用同一 errorId 写入脱敏诊断日志。
     *
     * <p>该异常已经完成供应商错误分类，不能落入通用运行时处理而丢失稳定错误码；其消息不包含
     * API Key、完整 URL 或供应商响应正文。供应商响应只会作为附加诊断内容经脱敏后写入服务端日志。</p>
     *
     * @param failure 已分类的模型目录发现失败
     * @param request 当前 HTTP 请求，用于保持响应关联编号
     * @return 包含稳定错误码和脱敏提示的响应
     */
    @ExceptionHandler(ModelCatalogDiscoveryException.class)
    public ResponseEntity<ApiErrorResponse> modelCatalogDiscoveryFailure(
            ModelCatalogDiscoveryException failure,
            HttpServletRequest request
    ) {
        diagnosticLogger.error(failure.diagnosticContext(), failure, failure.upstreamResponse());
        return response(failure.status(), failure.errorCode(), failure.getMessage(), request);
    }

    private static boolean approvalRequest(HttpServletRequest request) {
        return request.getRequestURI().contains("/approvals");
    }

    /** 历史查询的分页错误不是审批决定错误，只有写入决定的入口使用决定专属错误码。 */
    private static boolean approvalDecisionRequest(HttpServletRequest request) {
        return approvalRequest(request) && request.getRequestURI().endsWith("/decision/stream");
    }

    @ExceptionHandler(RuntimeException.class)
    /**
     * 兜底处理未分类运行时异常，避免向客户端泄露内部细节。
     *
     * @param ignored 仅用于满足回调签名、不参与业务判断的参数。
     */
    public ResponseEntity<ApiErrorResponse> runtimeFailure(RuntimeException failure, HttpServletRequest request) {
        return failedResponse(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "服务内部错误", failure, request);
    }

    /**
     * 构造带错误码、消息和时间戳的 API 响应。
     *
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     * @param code 稳定的业务错误码。
     * @param message 处理结果或审计消息。
     */
    private ResponseEntity<ApiErrorResponse> failedResponse(HttpStatus status,
                                                            ApiErrorCode code,
                                                            String message,
                                                            Throwable failure,
                                                            HttpServletRequest request) {
        String errorId = RequestCorrelationFilter.errorIdOf(request);
        // 此属性只由已认证 Controller 写入，不读取任何客户端 tenant/资源头；未提供时保持既有通用日志边界。
        Object supplied = request.getAttribute(ErrorDiagnosticLogger.DiagnosticContext.class.getName());
        var context = supplied instanceof ErrorDiagnosticLogger.DiagnosticContext trusted
                ? new ErrorDiagnosticLogger.DiagnosticContext(errorId, trusted.boundary(), code.name(), trusted.tenantId(),
                    trusted.principalId(), trusted.agentId(), trusted.runId(), trusted.toolId(), trusted.toolCallId(), trusted.source())
                : ErrorDiagnosticLogger.DiagnosticContext.api(errorId, code.name(), request.getRequestURI());
        diagnosticLogger.error(context, failure);
        return response(status, code, message, errorId);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status,
                                                       ApiErrorCode code,
                                                       String message,
                                                       HttpServletRequest request) {
        return response(status, code, message, RequestCorrelationFilter.errorIdOf(request));
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status,
                                                       ApiErrorCode code,
                                                       String message,
                                                       String errorId) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, redactor.redact(message), Instant.now(), errorId));
    }
}
