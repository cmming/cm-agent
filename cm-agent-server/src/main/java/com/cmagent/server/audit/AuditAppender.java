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
import java.util.List;
import java.util.UUID;

@Component
/** 统一写入安全审计事件；审计失败按严格策略向上抛出，不静默忽略。 */
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

    /**
     * 写入一条经过脱敏的审计事件。
     *
     * @param tenantId 租户标识
     * @param principalId 操作主体标识；为空时记录为匿名主体
     * @param eventType 事件类型
     * @param resourceType 资源类型
     * @param resourceId 资源标识；为空时使用占位符
     * @param status 事件结果状态
     * @param message 事件描述，写入前会进行敏感信息脱敏
     * @throws AuditPersistenceException 审计存储失败时抛出
     */
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

    /**
     * 批量写入审计事件，并保证每条事件的消息都经过脱敏。
     *
     * @param writes 待写入的审计事件集合
     * @throws AuditPersistenceException 批量写入失败时抛出
     */
    public void appendAll(List<AuditWrite> writes) {
        try {
            repository.appendAll(writes.stream().map(this::toAuditEvent).toList());
        } catch (RuntimeException ex) {
            log.warn("批量写入审计事件失败。eventCount={}, reason={}",
                    writes == null ? 0 : writes.size(), redactor.redact(ex.getMessage()));
            throw new AuditPersistenceException("审计写入失败", ex);
        }
    }

    /**
     * 记录一次权限拒绝事件。
     *
     * @param principal 发起请求的认证主体及其租户信息
     * @param resourceType 被访问资源类型
     * @param resourceId 被访问资源标识
     * @param permission 所需权限
     * @param reason 拒绝原因
     * @throws AuditPersistenceException 审计写入失败时抛出
     */
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

    private AuditEvent toAuditEvent(AuditWrite write) {
        return new AuditEvent(
                UUID.randomUUID(),
                write.tenantId(),
                write.principalId() == null || write.principalId().isBlank() ? "anonymous" : write.principalId(),
                write.eventType(),
                write.resourceType(),
                write.resourceId() == null || write.resourceId().isBlank() ? "-" : write.resourceId(),
                write.status(),
                write.message() == null || write.message().isBlank() ? "-" : redactor.redact(write.message()),
                Instant.now()
        );
    }

    public record AuditWrite(
            UUID tenantId,
            String principalId,
            String eventType,
            String resourceType,
            String resourceId,
            String status,
            String message
    ) {
    }
}
