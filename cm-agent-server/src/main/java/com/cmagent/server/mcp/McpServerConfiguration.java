package com.cmagent.server.mcp;

import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cm-agent.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfiguration {

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
            ToolOutputSanitizer sanitizer
    ) {
        return new McpPublishedToolCatalog(
                tools, httpConfigs, publications, registry, executions, permissions, audits, objectMapper, sanitizer
        );
    }

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
