package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.persistence.JdbcClientProjectFenceAcknowledgements;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClientProjectFenceServiceTest {
    @Test void 读取不存在或不属于设备的运行返回同号拒绝审计() {
        var rows = mock(JdbcClientProjectFenceAcknowledgements.class);
        var audit = mock(AuditAppender.class);
        var diagnostics = mock(ErrorDiagnosticLogger.class);
        var device = mock(LocalClientDevice.class);
        var tenant = UUID.randomUUID(); var run = UUID.randomUUID();
        when(device.tenantId()).thenReturn(tenant); when(device.ownerId()).thenReturn("owner");
        when(rows.current(device, run)).thenReturn(java.util.Optional.empty());
        var service = new ClientProjectFenceService(rows, audit, diagnostics);
        var failure = catchThrowableOfType(() -> service.current(device, run), LocalProjectAccessException.class);
        assertThat(failure.errorCode()).isEqualTo("CLIENT_PROJECT_FENCE_CONFLICT");
        assertThat(failure.status()).isEqualTo(409);
        var message = ArgumentCaptor.forClass(String.class);
        verify(audit).appendControlled(eq(tenant), eq("owner"), eq("CLIENT_PROJECT_FENCE_READ"),
                eq("CLIENT_PROJECT_RUN"), eq(run.toString()), eq("DENIED"), message.capture());
        assertThat(message.getValue()).contains(failure.errorId(), failure.errorCode());
    }

    @Test void 来源冲突返回同号安全审计且不能伪装成功() {
        var rows = mock(JdbcClientProjectFenceAcknowledgements.class);
        var audit = mock(AuditAppender.class);
        var diagnostics = mock(ErrorDiagnosticLogger.class);
        var device = mock(LocalClientDevice.class);
        var tenant = UUID.randomUUID(); var run = UUID.randomUUID();
        when(device.tenantId()).thenReturn(tenant); when(device.ownerId()).thenReturn("owner");
        var service = new ClientProjectFenceService(rows, audit, diagnostics);
        var failure = catchThrowableOfType(() -> service.acknowledge(device, run, 3), LocalProjectAccessException.class);
        assertThat(failure.errorCode()).isEqualTo("CLIENT_PROJECT_FENCE_CONFLICT");
        assertThat(failure.status()).isEqualTo(409);
        var message = ArgumentCaptor.forClass(String.class);
        verify(audit).appendControlled(eq(tenant), eq("owner"), eq("CLIENT_PROJECT_FENCE_ACK"),
                eq("CLIENT_PROJECT_RUN"), eq(run.toString()), eq("DENIED"), message.capture());
        assertThat(message.getValue()).contains(failure.errorId(), failure.errorCode());
        verifyNoInteractions(diagnostics);
    }

    @Test void 仓储异常返回同号诊断且不泄露原始原因() {
        var rows = mock(JdbcClientProjectFenceAcknowledgements.class);
        var audit = mock(AuditAppender.class);
        var diagnostics = mock(ErrorDiagnosticLogger.class);
        var device = mock(LocalClientDevice.class);
        var tenant = UUID.randomUUID(); var run = UUID.randomUUID();
        when(device.tenantId()).thenReturn(tenant); when(device.ownerId()).thenReturn("owner");
        when(rows.acknowledge(device, run, 1)).thenThrow(new IllegalStateException("secret=example https://internal.invalid"));
        var service = new ClientProjectFenceService(rows, audit, diagnostics);
        var failure = catchThrowableOfType(() -> service.acknowledge(device, run, 1), LocalProjectAccessException.class);
        assertThat(failure.errorCode()).isEqualTo("CLIENT_PROJECT_FENCE_UNAVAILABLE");
        assertThat(failure.status()).isEqualTo(503);
        assertThat(failure.getMessage()).doesNotContain("secret", "internal.invalid");
        var context = ArgumentCaptor.forClass(ErrorDiagnosticLogger.DiagnosticContext.class);
        var logged = ArgumentCaptor.forClass(Throwable.class);
        verify(diagnostics).error(context.capture(), logged.capture(), eq("CLIENT_PROJECT_RUN"), eq(run.toString()));
        assertThat(context.getValue().errorId()).isEqualTo(failure.errorId());
        assertThat(logged.getValue().getMessage()).doesNotContain("secret", "internal.invalid");
    }
}
