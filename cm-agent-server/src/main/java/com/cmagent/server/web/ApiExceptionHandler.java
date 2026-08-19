package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiErrorResponse;
import com.cmagent.server.audit.AuditPersistenceException;
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
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数不合法", request);
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
                    ApiErrorCode.VALIDATION_FAILED,
                    exception.getReason() == null || exception.getReason().isBlank()
                            ? "请求参数不合法"
                            : exception.getReason(),
                    request
            );
            case UNAUTHORIZED -> response(status, ApiErrorCode.UNAUTHORIZED, "未登录或令牌无效", request);
            case FORBIDDEN -> response(status, ApiErrorCode.FORBIDDEN, "没有权限执行该操作", request);
            case CONFLICT -> response(
                    status,
                    ApiErrorCode.VALIDATION_FAILED,
                    exception.getReason() == null || exception.getReason().isBlank()
                            ? "请求资源冲突"
                            : exception.getReason(),
                    request
            );
            case NOT_FOUND -> response(status, ApiErrorCode.RUNTIME_ERROR, "请求资源不存在", request);
            default -> failedResponse(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "服务内部错误", exception, request);
        };
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
        diagnosticLogger.error(ErrorDiagnosticLogger.DiagnosticContext.api(errorId, code.name(), request.getRequestURI()), failure);
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
