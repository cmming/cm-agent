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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpServerConfigurationTest {

    @Test
    void 默认关闭时不注册任何McpBean和端点() {
        contextRunner().run(context -> {
            assertThat(context).doesNotHaveBean(McpServerProperties.class);
            assertThat(context).doesNotHaveBean(McpPublishedToolCatalog.class);
            assertThat(context).doesNotHaveBean(McpEndpointServlet.class);
            assertThat(context).doesNotHaveBean("mcpServletRegistration");
        });
    }

    @Test
    void 启用但白名单为空时启动失败() {
        contextRunner()
                .withPropertyValues("cm-agent.mcp.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("allowedOrigins"));
    }

    @Test
    void 启用后按同一配置注册自定义端点() {
        contextRunner()
                .withPropertyValues(
                        "cm-agent.mcp.enabled=true",
                        "cm-agent.mcp.endpoint=/tenant-mcp",
                        "cm-agent.mcp.allowed-origins[0]=https://client.example.test",
                        "cm-agent.mcp.allowed-hosts[0]=localhost:*"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(McpServerProperties.class);
                    assertThat(context).hasSingleBean(McpPublishedToolCatalog.class);
                    assertThat(context).hasSingleBean(McpEndpointServlet.class);
                    ServletRegistrationBean<?> registration = context.getBean(
                            "mcpServletRegistration", ServletRegistrationBean.class
                    );
                    assertThat(registration.getUrlMappings()).containsExactly("/tenant-mcp");
                    assertThat(registration.getServlet()).isSameAs(context.getBean(McpEndpointServlet.class));
                });
    }

    private ApplicationContextRunner contextRunner() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ApplicationContextRunner()
                .withUserConfiguration(McpServerConfiguration.class)
                .withBean(ToolDefinitionRepository.class, () -> mock(ToolDefinitionRepository.class))
                .withBean(HttpToolConfigRepository.class, () -> mock(HttpToolConfigRepository.class))
                .withBean(McpToolPublicationRepository.class, () -> mock(McpToolPublicationRepository.class))
                .withBean(ToolRegistry.class, () -> mock(ToolRegistry.class))
                .withBean(GovernedToolExecutionService.class, () -> mock(GovernedToolExecutionService.class))
                .withBean(PermissionEvaluator.class, () -> mock(PermissionEvaluator.class))
                .withBean(AuditAppender.class, () -> mock(AuditAppender.class))
                .withBean(ObjectMapper.class, () -> objectMapper)
                .withBean(ToolOutputSanitizer.class, () -> new ToolOutputSanitizer(objectMapper));
    }
}
