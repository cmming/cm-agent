package com.cmagent.server.web;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ClientProjectService;
import com.cmagent.server.runtime.LocalClientOperationService;
import com.cmagent.server.runtime.LocalClientPairingService;
import com.cmagent.server.runtime.LocalClientProofService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalClientSelectionCapabilityTest {
    @Test void 旧客户端签名正文不能领取包含本机确认要求的项目任务() {
        var proofs = mock(LocalClientProofService.class);
        var device = mock(LocalClientDevice.class);
        when(proofs.verifyAndConsume(any(), any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(device);
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientProjectService> provider = mock(ObjectProvider.class);
        var service = mock(ClientProjectService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.claim(device)).thenReturn(new ClientProjectService.ClaimDecision(null, null));
        var controller = new LocalClientController(mock(LocalClientPairingService.class), proofs,
                mock(LocalClientOperationService.class), mock(PermissionEvaluator.class),
                mock(AuditAppender.class), provider);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-CM-Client-Project-Protocol")).thenReturn("5");
        when(request.getHeader("X-CM-Device-Id")).thenReturn(UUID.randomUUID().toString());
        when(request.getHeader("X-CM-Challenge-Id")).thenReturn(UUID.randomUUID().toString());
        when(request.getHeader("X-CM-Signature")).thenReturn("signature");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/local-client/bootstrap/project-tasks/next");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] oldBody = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> controller.nextProjectTask(oldBody, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(426));
        verify(service, never()).claim(any());
        byte[] supported = "{\"selectionProtocol\":1,\"fenceProtocol\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(controller.nextProjectTask(supported, request).getStatusCode().value()).isEqualTo(204);
        verify(proofs).verifyAndConsume(any(), any(), anyString(), anyString(), anyString(), eq(supported), anyString());
        verify(service).claim(device);
    }
}
