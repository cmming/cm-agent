package com.cmagent.server.audit;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AuditAppender {
    private static final Logger log = LoggerFactory.getLogger(AuditAppender.class);

    private final AuditEventRepository repository;
    private final SensitiveDataRedactor redactor;

    @Autowired
    public AuditAppender(AuditEventRepository repository, SensitiveDataRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    public AuditAppender(AuditEventRepository repository) {
        this(repository, new SensitiveDataRedactor());
    }

    public void append(UUID tenantId,
                       String principalId,
                       String eventType,
                       String resourceType,
                       String resourceId,
                       String status,
                       String message) {
        try {
            repository.append(new AuditEvent(
                    UUID.randomUUID(),
                    tenantId,
                    principalId == null || principalId.isBlank() ? "anonymous" : principalId,
                    eventType,
                    resourceType,
                    resourceId == null || resourceId.isBlank() ? "-" : resourceId,
                    status,
                    message == null || message.isBlank() ? "-" : redactor.redact(message),
                    Instant.now()
            ));
        } catch (RuntimeException ex) {
            log.warn("写入审计事件失败。eventType={}, resourceType={}, resourceId={}, reason={}",
                    redactor.redact(eventType),
                    redactor.redact(resourceType),
                    redactor.redact(resourceId),
                    redactor.redact(ex.getMessage()));
            throw new AuditPersistenceException("审计写入失败", ex);
        }
    }

    public void accessDenied(PrincipalRef principal, String resourceType, String resourceId, String permission, String reason) {
        append(
                principal.tenantId(),
                principal.principalId(),
                "ACCESS_DENIED",
                resourceType,
                resourceId,
                "DENIED",
                "缺少权限 " + permission + ": " + reason
        );
    }
}
