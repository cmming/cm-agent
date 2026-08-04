package com.cmagent.api;

/**
 * 对外 API 使用的稳定错误码。
 *
 * <p>错误码用于客户端分支判断；面向用户的具体说明由响应中的
 * {@code message} 提供。</p>
 */
public enum ApiErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    TENANT_NOT_FOUND,
    AGENT_NOT_FOUND,
    TOOL_NOT_FOUND,
    TOOL_NOT_GRANTED,
    VALIDATION_FAILED,
    RUNTIME_ERROR,
    AUDIT_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    INTERNAL_ERROR
}
