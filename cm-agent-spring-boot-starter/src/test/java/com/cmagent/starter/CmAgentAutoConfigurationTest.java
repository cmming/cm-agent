package com.cmagent.starter;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolRegistry;
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
    /**
     * 验证 {@code provideDefaultCoreBeans} 所描述的业务行为。
     */
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
    /**
     * 验证 {@code bindCmAgentPropertiesDefaultsAndOverrides} 所描述的业务行为。
     */
    void bindCmAgentPropertiesDefaultsAndOverrides() {
        contextRunner.run(context -> {
            CmAgentProperties properties = context.getBean(CmAgentProperties.class);
            assertThat(properties.isFakeRuntimeEnabled()).isTrue();
            assertThat(properties.getDefaultTenantCode()).isEqualTo("default");
        });

        contextRunner.withPropertyValues(
                "cm-agent.fake-runtime-enabled=false",
                "cm-agent.default-tenant-code=tenant-a"
        ).run(context -> {
            CmAgentProperties properties = context.getBean(CmAgentProperties.class);
            assertThat(properties.isFakeRuntimeEnabled()).isFalse();
            assertThat(properties.getDefaultTenantCode()).isEqualTo("tenant-a");
        });
    }

    @Test
    /**
     * 验证 {@code disableFakeRuntimeSuppressesDefaultAgentRuntime} 所描述的业务行为。
     */
    void disableFakeRuntimeSuppressesDefaultAgentRuntime() {
        contextRunner.withPropertyValues("cm-agent.fake-runtime-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AgentRuntime.class));
    }

    @Test
    /**
     * 验证 {@code userDefinedBeansBackOffDefaults} 所描述的业务行为。
     */
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
    /**
     * 验证 {@code discoversAutoConfigurationViaBootImports} 所描述的业务行为。
     */
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
        /**
         * 验证 {@code agentRuntime} 所描述的业务行为。
         */
        AgentRuntime agentRuntime() {
            return request -> null;
        }

        @Bean
        /**
         * 验证 {@code permissionEvaluator} 所描述的业务行为。
         */
        PermissionEvaluator permissionEvaluator() {
            return (principal, permission) -> null;
        }

        @Bean
        /**
         * 验证 {@code toolAuthorizationPolicy} 所描述的业务行为。
         */
        ToolAuthorizationPolicy toolAuthorizationPolicy() {
            return (principal, agentId, tool, grants) -> null;
        }

        @Bean
        /**
         * 验证 {@code toolRegistry} 所描述的业务行为。
         */
        ToolRegistry toolRegistry() {
            return new ToolRegistry() {
                @Override
                /**
                 * 验证 {@code register} 所描述的业务行为。
                 *
                 * @param definition 测试辅助方法使用的 definition 参数
                 * @param executor 测试执行器
                 */
                public void register(com.cmagent.core.domain.ToolDefinition definition, com.cmagent.core.tool.ToolExecutor executor) {
                }

                @Override
                /**
                 * 验证 {@code find} 所描述的业务行为。
                 *
                 * @param toolId 测试工具标识
                 */
                public java.util.Optional<com.cmagent.core.domain.ToolDefinition> find(java.util.UUID toolId) {
                    return java.util.Optional.empty();
                }

                @Override
                /**
                 * 验证 {@code snapshot} 所描述的业务行为。
                 *
                 * @param toolId 测试工具标识
                 */
                public java.util.Optional<ToolRegistrationSnapshot> snapshot(java.util.UUID toolId) {
                    return java.util.Optional.empty();
                }

                @Override
                /**
                 * 验证 {@code execute} 所描述的业务行为。
                 *
                 * @param request 测试使用的请求对象
                 */
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
