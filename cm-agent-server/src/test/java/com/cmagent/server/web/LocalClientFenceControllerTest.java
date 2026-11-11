package com.cmagent.server.web;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ClientProjectFenceService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalClientFenceControllerTest {
    @Test void 代次确认只使用签名正文和验签设备() {
        var proofs = mock(LocalClientProofService.class);
        var device = mock(LocalClientDevice.class);
        when(proofs.verifyAndConsume(any(), any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(device);
        var service = mock(ClientProjectFenceService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ClientProjectFenceService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        var controller = new LocalClientController(mock(LocalClientPairingService.class), proofs,
                mock(LocalClientOperationService.class), mock(PermissionEvaluator.class), mock(AuditAppender.class));
        controller.configureFenceServices(provider);
        var request = mock(HttpServletRequest.class);
        UUID deviceId = UUID.randomUUID(), challenge = UUID.randomUUID(), run = UUID.randomUUID();
        when(request.getHeader("X-CM-Client-Project-Protocol")).thenReturn("5");
        when(request.getHeader("X-CM-Device-Id")).thenReturn(deviceId.toString());
        when(request.getHeader("X-CM-Challenge-Id")).thenReturn(challenge.toString());
        when(request.getHeader("X-CM-Signature")).thenReturn("signature");
        when(request.getMethod()).thenReturn("POST");
        String requestPath = "/api/local-client/bootstrap/project-runs/" + run + "/fence-ack";
        when(request.getRequestURI()).thenReturn(requestPath);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] body = "{\"epoch\":2}".getBytes(StandardCharsets.US_ASCII);
        assertThat(controller.acknowledgeProjectFence(run, body, request).getStatusCode().value()).isEqualTo(204);
        verify(proofs).verifyAndConsume(eq(deviceId), eq(challenge), eq("signature"), eq("POST"),
                eq(requestPath), eq(body), eq("127.0.0.1"));
        verify(service).acknowledge(device, run, 2);
        for (String invalid : new String[]{"{}", "{\"epoch\":0}", "{\"epoch\":1,\"owner\":\"other\"}",
                "{\"epoch\":9223372036854775808}"}) {
            assertThatThrownBy(() -> controller.acknowledgeProjectFence(run,
                    invalid.getBytes(StandardCharsets.US_ASCII), request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        }
        String currentPath = "/api/local-client/bootstrap/project-runs/" + run + "/fence-current";
        when(request.getRequestURI()).thenReturn(currentPath);
        var view = new ClientProjectFenceService.FenceView(run, UUID.randomUUID(), "a".repeat(64), 2, "RECOVERING", false);
        when(service.current(device, run)).thenReturn(view);
        byte[] capability = "{\"fenceProtocol\":1}".getBytes(StandardCharsets.US_ASCII);
        assertThat(controller.currentProjectFence(run, capability, request).getBody()).isEqualTo(view);
        verify(proofs).verifyAndConsume(eq(deviceId), eq(challenge), eq("signature"), eq("POST"),
                eq(currentPath), eq(capability), eq("127.0.0.1"));
        verify(service).current(device, run);
        assertThatThrownBy(() -> controller.currentProjectFence(run, "{}".getBytes(StandardCharsets.US_ASCII), request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(426));
        verifyNoMoreInteractions(service);
    }
}
