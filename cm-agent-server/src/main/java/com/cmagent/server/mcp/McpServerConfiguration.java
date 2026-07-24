package com.cmagent.server.mcp;

import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cm-agent.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({McpServerProperties.class, HttpToolProperties.class})
/** MCP 服务端条件化配置；默认关闭，启用后仍受 JWT 和工具权限保护。 */
public class McpServerConfiguration {

    /**
     * 创建当前租户 MCP 工具目录。
     *
     * @param tools              工具定义 Repository
     * @param httpConfigs        HTTP 工具配置 Repository
     * @param publications       MCP 发布 Repository
     * @param registry           工具注册表
     * @param executions         受治理的工具执行服务
     * @param permissions        权限评估器
     * @param audits             审计写入器
     * @param objectMapper       JSON 映射器
     * @param sanitizer          工具输出脱敏器
     * @param httpToolProperties HTTP 工具运行时限制
     * @return MCP 已发布工具目录
     */
    @Bean
    McpPublishedToolCatalog mcpPublishedToolCatalog(
            ToolDefinitionRepository tools,
            HttpToolConfigRepository httpConfigs,
            McpToolPublicationRepository publications,
            ToolRegistry registry,
            GovernedToolExecutionService executions,
            PermissionEvaluator permissions,
            AuditAppender audits,
            ObjectMapper objectMapper,
            ToolOutputSanitizer sanitizer,
            HttpToolProperties httpToolProperties
    ) {
        return new McpPublishedToolCatalog(
                tools, httpConfigs, publications, registry, executions, permissions, audits, objectMapper, sanitizer,
                httpToolProperties
        );
    }

    /**
     * 创建 MCP HTTP Servlet。
     *
     * @param properties   MCP 端点和白名单配置
     * @param catalog      MCP 工具目录
     * @param permissions  权限评估器
     * @param audits       审计写入器
     * @param objectMapper JSON 映射器
     * @return MCP 请求处理 Servlet
     */
    @Bean
    McpEndpointServlet mcpEndpointServlet(
            McpServerProperties properties,
            McpPublishedToolCatalog catalog,
            PermissionEvaluator permissions,
            AuditAppender audits,
            ObjectMapper objectMapper
    ) {
        return new McpEndpointServlet(properties, catalog, permissions, audits, objectMapper);
    }

    /**
     * 将 MCP Servlet 注册到配置的 HTTP 端点。
     *
     * @param servlet    MCP 请求处理 Servlet
     * @param properties MCP 端点配置
     * @return Servlet 注册 Bean
     */
    @Bean
    ServletRegistrationBean<McpEndpointServlet> mcpServletRegistration(
            McpEndpointServlet servlet,
            McpServerProperties properties
    ) {
        ServletRegistrationBean<McpEndpointServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getEndpoint());
        registration.setName("cmAgentMcpEndpoint");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }
}
