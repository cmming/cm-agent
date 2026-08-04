package com.cmagent.server.audit;

import com.cmagent.core.runtime.ToolInvocationInfrastructureException;

/**
 * 审计持久化失败异常，表示本次操作不能在无审计记录的情况下继续。
 */
public class AuditPersistenceException extends ToolInvocationInfrastructureException {
    /**
     * 表示 {@code AuditPersistenceException} 对应失败场景的受控异常。
     *
     * @param message 处理结果或审计消息。
     * @param cause 触发当前失败的原始异常。
     */
    public AuditPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
