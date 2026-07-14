package com.cmagent.server.audit;

public class AuditPersistenceException extends RuntimeException {

    public AuditPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
