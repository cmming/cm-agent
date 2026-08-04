package com.cmagent.server.mcp;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MCP Streamable HTTP 入口，将 MCP 会话请求转交给已发布工具目录。
 */
public class McpEndpointServlet extends HttpServlet {
    private static final String RESOURCE_ID = "/mcp";

    private final McpServerProperties properties;
    private final McpPublishedToolCatalog catalog;
    private final PermissionEvaluator permissions;
    private final AuditAppender audits;
    private final ObjectMapper objectMapper;
    private final RequestServerFactory serverFactory;
    /**
     * 创建 {@code McpEndpointServlet} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param catalog 当前租户可用的 MCP 工具目录。
     * @param permissions 主体拥有的权限集合。
     * @param audits 本次操作产生的审计记录集合。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     */
    public McpEndpointServlet(
            McpServerProperties properties,
            McpPublishedToolCatalog catalog,
            PermissionEvaluator permissions,
            AuditAppender audits,
            ObjectMapper objectMapper
    ) {
        this(properties, catalog, permissions, audits, objectMapper, null);
    }
    /**
     * 创建 {@code McpEndpointServlet} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param catalog 当前租户可用的 MCP 工具目录。
     * @param permissions 主体拥有的权限集合。
     * @param audits 本次操作产生的审计记录集合。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param serverFactory 按租户和认证主体创建 MCP 服务实例的工厂
     */
    McpEndpointServlet(
            McpServerProperties properties,
            McpPublishedToolCatalog catalog,
            PermissionEvaluator permissions,
            AuditAppender audits,
            ObjectMapper objectMapper,
            RequestServerFactory serverFactory
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.permissions = Objects.requireNonNull(permissions, "permissions 不能为空");
        this.audits = Objects.requireNonNull(audits, "audits 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.serverFactory = serverFactory == null ? this::createOfficialServer : serverFactory;
    }

    @Override
    /**
     * 处理当前 MCP HTTP 请求，并将响应委托给请求级协议服务。
     *
     * @param request 当前 MCP HTTP 请求，提供认证头、会话信息和请求体
     * @param response 当前 HTTP 响应。
     */
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        PrincipalRef principal = currentPrincipal();
        if (principal == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或令牌无效");
            return;
        }
        AuthorizationDecision decision = permissions.check(principal, McpPublishedToolCatalog.INVOKE_PERMISSION);
        if (!decision.allowed()) {
            try {
                audits.accessDenied(principal, "MCP", RESOURCE_ID,
                        McpPublishedToolCatalog.INVOKE_PERMISSION, decision.reason());
            } catch (AuditPersistenceException exception) {
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP 服务暂不可用");
                return;
            }
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "没有权限调用 MCP 端点");
            return;
        }

        RequestServer server;
        try {
            server = serverFactory.create(principal);
        } catch (RuntimeException exception) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP 服务暂不可用");
            return;
        }
        try {
            server.service(request, response);
        } finally {
            server.close();
        }
    }

    /**
     * 创建并返回流程所需对象。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     */
    private RequestServer createOfficialServer(PrincipalRef principal) {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        DefaultServerTransportSecurityValidator officialValidator = DefaultServerTransportSecurityValidator.builder()
                .allowedOrigins(properties.getAllowedOrigins())
                .allowedHosts(properties.getAllowedHosts())
                .build();
        ServerTransportSecurityValidator strictValidator = headers -> {
            rejectAmbiguousHeader(headers, "Origin", HttpServletResponse.SC_FORBIDDEN);
            rejectAmbiguousHeader(headers, "Host", 421);
            officialValidator.validateHeaders(headers);
        };
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint(properties.getEndpoint())
                .jsonMapper(jsonMapper)
                .securityValidator(strictValidator)
                .build();
        McpStatelessSyncServer server;
        try {
            server = McpServer.sync(transport)
                    .serverInfo("cm-agent", "0.1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .validateToolInputs(true)
                    .tools(catalog.specifications(principal))
                    .build();
        } catch (RuntimeException exception) {
            transport.destroy();
            throw exception;
        }
        return new RequestServer() {
            @Override
            /**
             * 处理当前 MCP HTTP 请求，并将响应委托给请求级协议服务。
             *
             * @param request 当前 MCP HTTP 请求，提供认证头和会话信息
             * @param response 当前 HTTP 响应。
             */
            public void service(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, IOException {
                transport.service(request, response);
            }

            @Override
            /**
             * 关闭并释放当前对象占用的资源。
             */
            public void close() {
                server.close();
            }
        };
    }

    /**
     * 拒绝不满足安全或一致性要求的输入。
     *
     * @param headers 待发送或合并的 HTTP 请求头。
     * @param headerName 待读取的 HTTP 请求头名称
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     */
    private void rejectAmbiguousHeader(Map<String, List<String>> headers, String headerName, int status)
            throws ServerTransportSecurityException {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!headerName.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null || values.size() != 1 || isAmbiguous(values.getFirst())) {
                throw new ServerTransportSecurityException(status, "Invalid " + headerName + " header");
            }
        }
    }

    /**
     * 判断方法名所描述的业务条件是否成立。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private boolean isAmbiguous(String value) {
        return value == null || value.isBlank() || value.contains(",") || value.contains("\r") || value.contains("\n");
    }

    /**
     * 从当前请求线程读取已认证主体。
     */
    private PrincipalRef currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            return null;
        }
        return new PrincipalRef(
                session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions())
        );
    }

    /**
     * 写出规范化结果。
     *
     * @param response 当前 HTTP 响应。
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     * @param message 处理结果或审计消息。
     */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }

    /**
     * 定义 {@code RequestServerFactory} 的内部协作契约。
     */
    interface RequestServerFactory {
        /**
         * 创建基于认证主体的请求级 MCP 服务。
         *
         * @param principal 当前认证主体，提供租户、身份和权限上下文。
         */
        RequestServer create(PrincipalRef principal);
    }

    /**
     * 定义 {@code RequestServer} 的内部协作契约。
     */
    interface RequestServer extends AutoCloseable {
        /**
         * 处理当前 MCP HTTP 请求，并将响应委托给请求级协议服务。
         *
         * @param request 当前 MCP HTTP 请求，提供认证头和会话信息
         * @param response 当前 HTTP 响应。
         */
        void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;

        @Override
        /**
         * 关闭并释放当前对象占用的资源。
         */
        void close();
    }
}
