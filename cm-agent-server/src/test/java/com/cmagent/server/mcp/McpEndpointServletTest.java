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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class McpEndpointServletTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000811");

    @AfterEach
    /**
     * 验证或支持 {@code clearSecurityContext} 所描述的测试场景。
     */
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
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
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 每次请求创建独立Server并在异常时关闭(CapturedOutput output) throws Exception {
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
                        /**
                         * 验证或支持 {@code service} 所描述的测试场景。
                         *
                         * @param request 测试使用的请求对象
                         * @param response 测试使用的 HTTP 响应
                         */
                        public void service(jakarta.servlet.http.HttpServletRequest request,
                                            jakarta.servlet.http.HttpServletResponse response) throws ServletException {
                            throw new ServletException("测试服务异常");
                        }

                        @Override
                        /**
                         * 模拟或记录 Agent 资源关闭动作。
                         */
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
        assertThat(output).contains("MCP 请求处理异常")
                .contains("failureType=ServletException")
                .doesNotContain("token");
    }

    @Test
    /**
     * 验证未认证请求会留下不包含凭据的诊断日志。
     */
    void 未认证请求记录401且不创建Server(CapturedOutput output) throws Exception {
        McpEndpointServlet.RequestServerFactory factory = mock(McpEndpointServlet.RequestServerFactory.class);
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), mock(McpPublishedToolCatalog.class), mock(PermissionEvaluator.class),
                mock(AuditAppender.class), new ObjectMapper(), factory
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(post(), response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(output).contains("MCP 请求认证失败")
                .contains("method=POST")
                .contains("endpoint=/mcp")
                .contains("status=401")
                .doesNotContain("Authorization");
        verifyNoInteractions(factory);
    }

    @Test
    /**
     * 验证请求级服务创建失败会记录异常类型，但不会记录异常消息中的敏感内容。
     */
    void Server创建失败记录脱敏诊断日志(CapturedOutput output) throws Exception {
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        McpEndpointServlet.RequestServerFactory factory = mock(McpEndpointServlet.RequestServerFactory.class);
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), mock(McpPublishedToolCatalog.class), permissions,
                mock(AuditAppender.class), new ObjectMapper(), factory
        );
        PrincipalRef principal = authenticate(Set.of(McpPublishedToolCatalog.INVOKE_PERMISSION));
        when(permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION))
                .thenReturn(AuthorizationDecision.allow());
        when(factory.create(principal)).thenThrow(new IllegalStateException("Bearer server-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(post(), response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(output).contains("MCP 请求级服务创建失败")
                .contains("failureType=IllegalStateException")
                .doesNotContain("server-secret");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 缺少权限返回403写拒绝审计且不创建Server(CapturedOutput output) throws Exception {
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        AuditAppender audits = mock(AuditAppender.class);
        McpEndpointServlet.RequestServerFactory factory = mock(McpEndpointServlet.RequestServerFactory.class);
        McpEndpointServlet servlet = new McpEndpointServlet(
                properties(), mock(McpPublishedToolCatalog.class), permissions, audits, new ObjectMapper(), factory
        );
        PrincipalRef principal = authenticate(Set.of());
        when(permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION))
                .thenReturn(AuthorizationDecision.deny("Bearer denied-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(post(), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("没有权限").doesNotContain("缺少权限 tool:mcp:invoke");
        verify(audits).accessDenied(principal, "MCP", "/mcp",
                McpPublishedToolCatalog.INVOKE_PERMISSION, "Bearer denied-secret");
        verify(factory, never()).create(principal);
        assertThat(output).contains("MCP 请求权限拒绝")
                .contains("status=403")
                .doesNotContain("denied-secret");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 官方Transport处理Get405并拒绝多值Origin和Host(CapturedOutput output) throws Exception {
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
        commaHost.addHeader("Host", "localhost:8080,evil.example.test");
        MockHttpServletResponse hostResponse = new MockHttpServletResponse();
        servlet.service(commaHost, hostResponse);
        assertThat(hostResponse.getStatus()).isIn(403, 421);

        MockHttpServletRequest disallowedOrigin = post();
        disallowedOrigin.removeHeader("Origin");
        disallowedOrigin.addHeader("Origin", "https://evil.example.test");
        MockHttpServletResponse disallowedOriginResponse = new MockHttpServletResponse();
        servlet.service(disallowedOrigin, disallowedOriginResponse);
        assertThat(disallowedOriginResponse.getStatus()).isEqualTo(403);

        assertThat(output).contains("MCP 请求处理返回失败状态")
                .contains("MCP 请求头格式不明确")
                .contains("headerName=Origin")
                .contains("headerName=Host")
                .contains("MCP 请求头未通过 Origin/Host 白名单校验")
                .doesNotContain("evil.example.test");
    }

    /**
     * 验证或支持 {@code properties} 所描述的测试场景。
     */
    private static McpServerProperties properties() {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(true);
        properties.setEndpoint("/mcp");
        properties.setAllowedOrigins(List.of("https://client.example.test"));
        properties.setAllowedHosts(List.of("localhost:*"));
        properties.afterPropertiesSet();
        return properties;
    }

    /**
     * 验证或支持 {@code authenticate} 所描述的测试场景。
     *
     * @param permissions 测试辅助方法使用的 permissions 参数
     */
    private static PrincipalRef authenticate(Set<String> permissions) {
        JwtService.JwtSession session = new JwtService.JwtSession(
                TENANT, "mcp-user", "MCP 用户", List.copyOf(permissions)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(session, "token", List.of())
        );
        return new PrincipalRef(TENANT, "mcp-user", "MCP 用户", permissions);
    }

    /**
     * 验证或支持 {@code post} 所描述的测试场景。
     */
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
