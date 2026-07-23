package com.cmagent.server.mcp;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpEndpointServletTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000811");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void properties默认关闭且启用时白名单必须非空() {
        McpServerProperties defaults = new McpServerProperties();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getEndpoint()).isEqualTo("/mcp");
        assertThat(defaults.getAllowedOrigins()).isEmpty();
        assertThat(defaults.getAllowedHosts()).isEmpty();

        defaults.setEnabled(true);
        assertThatThrownBy(defaults::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowedOrigins");
        defaults.setAllowedOrigins(List.of("https://client.example.test"));
        assertThatThrownBy(defaults::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowedHosts");
    }

    @Test
    void 每次请求创建独立Server并在异常时关闭() throws Exception {
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        AuditAppender audits = mock(AuditAppender.class);
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<PrincipalRef> captured = new AtomicReference<>();
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), mock(McpPublishedToolCatalog.class), permissions, audits, new ObjectMapper(),
                principal -> {
                    creates.incrementAndGet();
                    captured.set(principal);
                    return new McpEndpointServlet.RequestServer() {
                        @Override
                        public void service(jakarta.servlet.http.HttpServletRequest request,
                                            jakarta.servlet.http.HttpServletResponse response) throws ServletException {
                            throw new ServletException("测试服务异常");
                        }

                        @Override
                        public void close() {
                            closes.incrementAndGet();
                        }
                    };
                }
        );
        PrincipalRef principal = authenticate(Set.of(McpPublishedToolCatalog.INVOKE_PERMISSION));
        when(permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION)).thenReturn(AuthorizationDecision.allow());

        assertThatThrownBy(() -> servlet.service(post(), new MockHttpServletResponse()))
                .isInstanceOf(ServletException.class)
                .hasMessage("测试服务异常");
        assertThatThrownBy(() -> servlet.service(post(), new MockHttpServletResponse()))
                .isInstanceOf(ServletException.class);

        assertThat(creates).hasValue(2);
        assertThat(closes).hasValue(2);
        assertThat(captured.get()).isEqualTo(principal);
    }

    @Test
    void 缺少权限返回403写拒绝审计且不创建Server() throws Exception {
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        AuditAppender audits = mock(AuditAppender.class);
        McpEndpointServlet.RequestServerFactory factory = mock(McpEndpointServlet.RequestServerFactory.class);
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), mock(McpPublishedToolCatalog.class), permissions, audits, new ObjectMapper(), factory
        );
        PrincipalRef principal = authenticate(Set.of());
        when(permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION))
                .thenReturn(AuthorizationDecision.deny("缺少权限"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(post(), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("没有权限").doesNotContain("缺少权限 tool:mcp:invoke");
        verify(audits).accessDenied(principal, "MCP", "/mcp",
                McpPublishedToolCatalog.INVOKE_PERMISSION, "缺少权限");
        verify(factory, never()).create(principal);
    }

    @Test
    void 官方Transport处理Get405并拒绝多值Origin和Host() throws Exception {
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        PrincipalRef principal = authenticate(Set.of(McpPublishedToolCatalog.INVOKE_PERMISSION));
        when(permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION)).thenReturn(AuthorizationDecision.allow());
        McpPublishedToolCatalog catalog = mock(McpPublishedToolCatalog.class);
        when(catalog.specifications(principal)).thenReturn(List.of());
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), catalog, permissions, mock(AuditAppender.class), new ObjectMapper()
        );
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        MockHttpServletResponse getResponse = new MockHttpServletResponse();

        servlet.service(get, getResponse);

        assertThat(getResponse.getStatus()).isEqualTo(405);

        MockHttpServletRequest duplicateOrigin = post();
        duplicateOrigin.addHeader("Origin", "https://client.example.test");
        duplicateOrigin.addHeader("Origin", "https://evil.example.test");
        MockHttpServletResponse originResponse = new MockHttpServletResponse();
        servlet.service(duplicateOrigin, originResponse);
        assertThat(originResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest commaHost = post();
        commaHost.addHeader("Origin", "https://client.example.test");
        commaHost.addHeader("Host", "localhost:8080,evil.example.test");
        MockHttpServletResponse hostResponse = new MockHttpServletResponse();
        servlet.service(commaHost, hostResponse);
        assertThat(hostResponse.getStatus()).isIn(403, 421);
    }

    private static McpServerProperties properties() {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(true);
        properties.setEndpoint("/mcp");
        properties.setAllowedOrigins(List.of("https://client.example.test"));
        properties.setAllowedHosts(List.of("localhost:*"));
        properties.afterPropertiesSet();
        return properties;
    }

    private static PrincipalRef authenticate(Set<String> permissions) {
        JwtService.JwtSession session = new JwtService.JwtSession(
                TENANT, "mcp-user", "MCP 用户", List.copyOf(permissions)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(session, "token", List.of())
        );
        return new PrincipalRef(TENANT, "mcp-user", "MCP 用户", permissions);
    }

    private static MockHttpServletRequest post() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.addHeader("Origin", "https://client.example.test");
        request.addHeader("Host", "localhost:8080");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
