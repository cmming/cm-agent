package com.cmagent.server.runtime;

import com.cmagent.server.config.ClientProjectProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientProjectRequestBoundaryTest {
    @Test void 只接受精确同源和专用行动头() {
        var boundary = new ClientProjectRequestBoundary(properties());
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getLocalAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("Host")).thenReturn("127.0.0.1:8080");
        when(request.getHeader("Origin")).thenReturn("http://127.0.0.1:8080");
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
        when(request.getHeader("X-CM-Agent-Client-Project")).thenReturn("1");

        assertThat(boundary.permits(request)).isTrue();
    }

    @Test void 非回环来源和转发头不能扩大权限() {
        var boundary = new ClientProjectRequestBoundary(properties());
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getLocalAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("Host")).thenReturn("127.0.0.1:8080");
        when(request.getHeader("X-CM-Agent-Client-Project")).thenReturn("1");

        assertThat(boundary.permits(request)).isFalse();
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getLocalAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("Forwarded")).thenReturn("for=10.0.0.2");
        assertThat(boundary.permits(request)).isFalse();
    }

    private static ClientProjectProperties properties() {
        var properties = new ClientProjectProperties();
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of("http://127.0.0.1:8080"));
        properties.validate();
        return properties;
    }
}
