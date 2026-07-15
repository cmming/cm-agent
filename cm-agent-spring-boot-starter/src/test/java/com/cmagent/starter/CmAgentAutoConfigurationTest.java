package com.cmagent.starter;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CmAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CmAgentAutoConfiguration.class));

    @Test
    void provideDefaultCoreBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CmAgentProperties.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(PermissionEvaluator.class);
            assertThat(context).hasSingleBean(ToolAuthorizationPolicy.class);
            assertThat(context).hasSingleBean(ToolRegistry.class);
        });
    }

    @Test
    void bindCmAgentPropertiesDefaultsAndOverrides() {
        contextRunner.run(context -> {
            CmAgentProperties properties = context.getBean(CmAgentProperties.class);
            assertThat(properties.isFakeRuntimeEnabled()).isTrue();
            assertThat(properties.getDefaultTenantCode()).isEqualTo("default");
        });

        contextRunner.withPropertyValues(
                "cm-agent.fake-runtime-enabled=false",
                "cm-agent.default-tenant-code=tenant-a",
                "cm-agent.agentscope.base-url=https://model.example/v1",
                "cm-agent.agentscope.api-key=test-key",
                "cm-agent.agentscope.timeout=15s"
        ).run(context -> {
            CmAgentProperties properties = context.getBean(CmAgentProperties.class);
            assertThat(properties.isFakeRuntimeEnabled()).isFalse();
            assertThat(properties.getDefaultTenantCode()).isEqualTo("tenant-a");
            assertThat(properties.getAgentscope().getBaseUrl()).isEqualTo("https://model.example/v1");
            assertThat(properties.getAgentscope().getApiKey()).isEqualTo("test-key");
            assertThat(properties.getAgentscope().getTimeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        });
    }

    @Test
    void createAgentScopeRuntimeWhenEnabled() {
        contextRunner.withPropertyValues(
                "cm-agent.fake-runtime-enabled=false",
                "cm-agent.agentscope.enabled=true",
                "cm-agent.agentscope.api-key=test-key",
                "cm-agent.agentscope.base-url=https://model.example/v1",
                "cm-agent.agentscope.timeout=15s"
        ).run(context -> {
            assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class);
            assertThat(context).getBean(AgentRuntime.class)
                    .isSameAs(context.getBean(AgentScopeRuntimeAdapter.class));
        });
    }

    @Test
    void disableFakeRuntimeSuppressesDefaultAgentRuntime() {
        contextRunner.withPropertyValues("cm-agent.fake-runtime-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AgentRuntime.class));
    }

    @Test
    void userDefinedBeansBackOffDefaults() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CmAgentAutoConfiguration.class))
                .withUserConfiguration(CustomBeansConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(PermissionEvaluator.class);
                    assertThat(context).hasSingleBean(ToolAuthorizationPolicy.class);
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                });
    }

    @Test
    void discoversAutoConfigurationViaBootImports() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableAutoConfigurationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CmAgentProperties.class);
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(PermissionEvaluator.class);
                    assertThat(context).hasSingleBean(ToolAuthorizationPolicy.class);
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeansConfiguration {

        @Bean
        AgentRuntime agentRuntime() {
            return request -> null;
        }

        @Bean
        PermissionEvaluator permissionEvaluator() {
            return (principal, permission) -> null;
        }

        @Bean
        ToolAuthorizationPolicy toolAuthorizationPolicy() {
            return (principal, agentId, tool, grants) -> null;
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry() {
                @Override
                public void register(com.cmagent.core.domain.ToolDefinition definition, com.cmagent.core.tool.ToolExecutor executor) {
                }

                @Override
                public java.util.Optional<com.cmagent.core.domain.ToolDefinition> find(java.util.UUID toolId) {
                    return java.util.Optional.empty();
                }

                @Override
                public com.cmagent.core.tool.ToolExecutionResult execute(com.cmagent.core.tool.ToolExecutionRequest request) {
                    return new com.cmagent.core.tool.ToolExecutionResult("custom", true);
                }
            };
        }
    }

    @EnableAutoConfiguration
    @Configuration(proxyBeanMethods = false)
    static class EnableAutoConfigurationConfiguration {
    }
}
