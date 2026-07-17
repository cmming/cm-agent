package com.cmagent.server.audit;

import com.cmagent.core.runtime.ToolInvocationInfrastructureException;

public class AuditPersistenceException extends ToolInvocationInfrastructureException {

    public AuditPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
