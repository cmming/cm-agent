package com.cmagent.server.audit;

import com.cmagent.core.runtime.ToolInvocationInfrastructureException;

/**
 * 审计持久化失败异常，表示本次操作不能在无审计记录的情况下继续。
 */
public class AuditPersistenceException extends ToolInvocationInfrastructureException {
    /**
     * AuditPersistenceException：处理该类内部的业务逻辑或辅助计算。
     */
    public AuditPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
