package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiErrorResponse;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.SensitiveDataRedactor;
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

    public ApiExceptionHandler() {
        this(new SensitiveDataRedactor());
    }

    @Autowired
    public ApiExceptionHandler(SensitiveDataRedactor redactor) {
        this.redactor = redactor;
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
    public ResponseEntity<ApiErrorResponse> validationFailure(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> persistenceFailure(DataAccessException ignored) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.PERSISTENCE_UNAVAILABLE, "数据服务暂不可用");
    }

    @ExceptionHandler(AuditPersistenceException.class)
    public ResponseEntity<ApiErrorResponse> auditPersistenceFailure(AuditPersistenceException ignored) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.AUDIT_UNAVAILABLE, "审计服务暂不可用");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> statusFailure(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return switch (status) {
            case BAD_REQUEST -> response(status, ApiErrorCode.VALIDATION_FAILED, "请求参数不合法");
            case UNAUTHORIZED -> response(status, ApiErrorCode.UNAUTHORIZED, "未登录或令牌无效");
            case FORBIDDEN -> response(status, ApiErrorCode.FORBIDDEN, "没有权限执行该操作");
            case CONFLICT -> response(status, ApiErrorCode.VALIDATION_FAILED, "请求资源已存在");
            case NOT_FOUND -> response(status, ApiErrorCode.RUNTIME_ERROR, "请求资源不存在");
            default -> response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "服务内部错误");
        };
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> runtimeFailure(RuntimeException ignored) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "服务内部错误");
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, ApiErrorCode code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, redactor.redact(message), Instant.now()));
    }
}
