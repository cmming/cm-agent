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
    CONVERSATION_NOT_FOUND,
    /** 审批请求不存在或不属于当前可信资源边界。 */
    TOOL_APPROVAL_NOT_FOUND,
    /** 审批决定缺失、重复或包含不属于当前请求的明细。 */
    TOOL_APPROVAL_INVALID_DECISION,
    /** 审批版本或状态已被并发修改。 */
    TOOL_APPROVAL_CONFLICT,
    /** 审批请求已超过服务端权威有效期。 */
    TOOL_APPROVAL_EXPIRED,
    TOOL_NOT_FOUND,
    TOOL_NOT_GRANTED,
    VALIDATION_FAILED,
    RUNTIME_ERROR,
    AUDIT_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    INTERNAL_ERROR
}
