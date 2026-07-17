package com.cmagent.server.audit;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAppenderTest {

    @Test
    void appendRedactsSensitiveCredentialsBeforePersistingAuditMessage() {
        CapturingAuditEventRepository repository = new CapturingAuditEventRepository();
        AuditAppender appender = new AuditAppender(repository, new SensitiveDataRedactor());

        appender.append(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "auditor",
                "CONFIG_UPDATED",
                "CONFIG",
                "runtime",
                "SUCCEEDED",
                "password=audit-test-password apiKey=audit-test-api-key Bearer audit.test.token "
                        + "jdbc:postgresql://audit_user:audit_password@localhost:5432/cm_agent"
        );

        String message = repository.onlyEvent().message();
        assertThat(message)
                .contains("<已脱敏>")
                .doesNotContain("audit-test-password", "audit-test-api-key", "audit.test.token", "audit_user:audit_password");
    }

    @Test
    void appendRethrowsRepositoryFailure() {
        IllegalStateException cause = new IllegalStateException("database unavailable password=audit-test-password");
        AuditEventRepository repository = new AuditEventRepository() {
            @Override
            public void append(AuditEvent event) {
                throw cause;
            }

            @Override
            public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> new AuditAppender(repository, new SensitiveDataRedactor()).append(
                UUID.randomUUID(), "user", "EVENT", "RESOURCE", "id", "FAILED", "message"))
                .isInstanceOf(AuditPersistenceException.class)
                .isInstanceOf(ToolInvocationInfrastructureException.class)
                .hasMessage("审计写入失败")
                .hasCause(cause);
    }

    private static final class CapturingAuditEventRepository implements AuditEventRepository {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }

        @Override
        public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
            return events.stream().filter(event -> event.tenantId().equals(tenantId)).limit(limit).toList();
        }

        private AuditEvent onlyEvent() {
            assertThat(events).hasSize(1);
            return events.getFirst();
        }
    }
}
