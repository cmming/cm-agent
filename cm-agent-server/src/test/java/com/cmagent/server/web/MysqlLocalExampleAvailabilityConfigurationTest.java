package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.MysqlLocalExampleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MysqlLocalExampleAvailabilityConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("mysql"))
            .withUserConfiguration(LocalExampleTestConfiguration.class);

    @Test
    void mysqlMemory正常启动且不装配安装服务或端点() {
        contextRunner
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MysqlLocalExampleService.class);
                    assertThat(context).doesNotHaveBean(LocalToolExampleController.class);
                });
    }

    @Test
    void mysqlJdbc装配安装服务并由MockMvc提供目录端点() {
        contextRunner
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MysqlLocalExampleService.class);
                    assertThat(context).hasSingleBean(LocalToolExampleController.class);

                    MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(LocalToolExampleController.class)).build();
                    mvc.perform(get("/api/tools/local-examples").principal(authentication("tool:read")))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$").isArray())
                            .andExpect(jsonPath("$.length()").value(2));
                });
    }

    private static UsernamePasswordAuthenticationToken authentication(String... permissions) {
        JwtService.JwtSession session = new JwtService.JwtSession(MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID,
                "admin", "管理员", List.of(permissions));
        return new UsernamePasswordAuthenticationToken(session, "", List.of(() -> "ROLE_USER"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MysqlLocalExampleService.class, LocalToolExampleController.class})
    static class LocalExampleTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ToolDefinitionRepository toolDefinitionRepository() {
            return mock(ToolDefinitionRepository.class);
        }

        @Bean
        AuditEventRepository auditEventRepository() {
            return mock(AuditEventRepository.class);
        }

        @Bean
        AuditAppender auditAppender(AuditEventRepository repository) {
            return new AuditAppender(repository);
        }

        @Bean
        PermissionEvaluator permissionEvaluator() {
            return (principal, permission) -> AuthorizationDecision.allow();
        }

        @Bean
        MysqlLocalExampleCatalog mysqlLocalExampleCatalog(ObjectMapper objectMapper) {
            return new MysqlLocalExampleCatalog(objectMapper);
        }

        @Bean
        HttpToolProperties httpToolProperties() {
            return new HttpToolProperties();
        }

        @Bean
        ToolRuntimeReadiness toolRuntimeReadiness(HttpToolProperties properties) {
            return new ToolRuntimeReadiness(new InMemoryToolRegistry(), properties);
        }
    }
}
