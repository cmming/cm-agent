package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * 表示已被归类、可安全返回控制台的模型目录发现失败。
 *
 * <p>异常消息只允许使用本服务定义的中文摘要；供应商响应、完整目标地址和 API Key
 * 只能保留在受控的底层异常中供脱敏诊断日志使用，绝不能透传到浏览器。</p>
 */
public class ModelCatalogDiscoveryException extends RuntimeException {
    private final HttpStatus status;
    private final ApiErrorCode errorCode;
    private final ErrorDiagnosticLogger.DiagnosticContext diagnosticContext;

    /**
     * 创建已完成供应商失败归类的异常。
     *
     * @param status 要返回给调用方的 HTTP 状态
     * @param errorCode 前端和诊断日志共用的稳定错误码
     * @param message 不含密钥、完整地址和供应商正文的中文提示
     * @param diagnosticContext 仅包含可信服务端上下文的诊断字段
     * @param cause 原始失败原因，仅供服务端脱敏诊断定位
     */
    public ModelCatalogDiscoveryException(
            HttpStatus status,
            ApiErrorCode errorCode,
            String message,
            ErrorDiagnosticLogger.DiagnosticContext diagnosticContext,
            Throwable cause
    ) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status 不能为空");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode 不能为空");
        this.diagnosticContext = Objects.requireNonNull(diagnosticContext, "diagnosticContext 不能为空");
    }

    /**
     * @return 面向当前调用方的 HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return 前端可稳定识别的模型目录发现错误码
     */
    public ApiErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return 仅用于服务端脱敏日志的可信诊断上下文
     */
    public ErrorDiagnosticLogger.DiagnosticContext diagnosticContext() {
        return diagnosticContext;
    }
}
