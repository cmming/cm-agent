package com.cmagent.server.config;

import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeRuntimeConfigurationTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AgentScopeRuntimeConfiguration.class,
                    ModelCredentialEncryptionConfiguration.class,
                    ToolGatewayConfiguration.class,
                    RepositoryConfiguration.class)
            .withBean(SkillAccessGateway.class, () -> mock(SkillAccessGateway.class))
            .withPropertyValues(
                    "cm-agent.model-credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    @Test
    /**
     * 验证或支持 {@code enabledConfigurationProvidesRealRuntime} 所描述的测试场景。
     */
    void enabledConfigurationProvidesRealRuntime() {
        contextRunner.withPropertyValues(
                        "cm-agent.fake-runtime-enabled=false",
                        "cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class);
                });
    }

    @Test
    /**
     * 验证或支持 {@code fakeAndRealRuntimeCannotBeEnabledTogether} 所描述的测试场景。
     */
    void fakeAndRealRuntimeCannotBeEnabledTogether() {
        contextRunner.withPropertyValues(
                        "cm-agent.fake-runtime-enabled=true",
                        "cm-agent.agentscope.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("AgentScope 真实运行时与 fake runtime 不能同时启用"));
    }

    @Test
    /**
     * 验证自定义凭据提供者可以替代数据库默认实现。
     */
    void customCredentialProviderReplacesDatabaseProvider() {
        contextRunner.withBean(ModelCredentialProvider.class,
                        () -> (tenantId, modelConfigId) -> new ModelCredential("unit-test-custom-key"))
                .withPropertyValues("cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelCredentialProvider.class);
                    assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class);
                    assertThat(context.getBean(ModelCredentialProvider.class).resolve(TENANT_ID, MODEL_ID).apiKey())
                            .isEqualTo("unit-test-custom-key");
                });
    }

    @Test
    /**
     * 验证或支持 {@code customRuntimePreventsDefaultRuntimeAndCredentialBeans} 所描述的测试场景。
     */
    void customRuntimePreventsDefaultRuntimeAndCredentialBeans() {
        contextRunner.withBean(AgentRuntime.class, () -> request -> null)
                .withPropertyValues("cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).doesNotHaveBean(AgentScopeRuntimeAdapter.class);
                    assertThat(context).doesNotHaveBean(ModelCredentialProvider.class);
                });
    }

    @Test
    /**
     * 验证默认凭据提供者不再要求配置文件中的模型 API Key。
     */
    void defaultCredentialProviderDoesNotRequireYamlApiKey() {
        contextRunner.withPropertyValues("cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelCredentialProvider.class);
                });
    }

    @Test
    void 默认React且显式Harness装配可取消运行时() {
        contextRunner.withPropertyValues("cm-agent.agentscope.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AgentScopeRuntimeProperties.class).getExecutionMode()).isEqualTo("react");
            assertThat(context.getBean(AgentRuntime.class).supportsCancellation()).isTrue();
        });
        contextRunner.withPropertyValues("cm-agent.agentscope.enabled=true", "cm-agent.agentscope.execution-mode=harness")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentRuntime.class).supportsCancellation()).isTrue();
                    assertThat(context.getBean(com.cmagent.core.repository.RuntimeExecutionModeRepository.class).currentMode())
                            .isEqualTo("harness");
                });
    }

    @Test
    void 未知模式明确启动失败而非回退React() {
        contextRunner.withPropertyValues("cm-agent.agentscope.enabled=true", "cm-agent.agentscope.execution-mode=unknown")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("执行模式只支持 react 或 harness"));
    }

    @Test
    void 模式切换必须通过排空门禁() {
        var modes = new com.cmagent.server.store.InMemoryRuntimeExecutionModeRepository(() -> true);
        contextRunner.withBean(com.cmagent.core.repository.RuntimeExecutionModeRepository.class, () -> modes)
                .withPropertyValues("cm-agent.agentscope.enabled=true", "cm-agent.agentscope.execution-mode=harness")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("必须排空"));
        assertThat(modes.currentMode()).isEqualTo("react");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.cmagent.agentscope.AgentScopeExecutionMode.class)
    void 已装配实例在数据集模式改变后拒绝且不读取凭据(com.cmagent.agentscope.AgentScopeExecutionMode mode) {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var modes = new com.cmagent.server.store.InMemoryRuntimeExecutionModeRepository(() -> false);
        contextRunner.withBean(com.cmagent.core.repository.RuntimeExecutionModeRepository.class, () -> modes)
                .withBean(ModelCredentialProvider.class, () -> (tenant, model) -> {
                    reads.incrementAndGet(); return new ModelCredential("unit-test-placeholder");
                }).withPropertyValues("cm-agent.agentscope.enabled=true",
                        "cm-agent.agentscope.execution-mode=" + mode.configValue()).run(context -> {
                    assertThat(context).hasNotFailed();
                    // 配置测试没有注册在途 Run，因此可模拟另一实例已经完成排空后的切换。
                    modes.activate(mode == com.cmagent.agentscope.AgentScopeExecutionMode.REACT ? "harness" : "react");
                    var agent = new com.cmagent.core.domain.AgentDefinition(UUID.randomUUID(), TENANT_ID,
                            "测试 Agent", "", "测试", MODEL_ID, "fixture", 0.2, 3, true, java.util.List.of(), "tester", "tester");
                    var model = new com.cmagent.core.domain.ModelConfig(MODEL_ID, TENANT_ID,
                            com.cmagent.core.domain.ModelProviderType.OPENAI_COMPATIBLE, "测试模型", "https://example.invalid", "fixture", true);
                    var principal = new com.cmagent.api.PrincipalRef(TENANT_ID, "tester", "测试", java.util.Set.of("agent:run"));
                    var request = new com.cmagent.core.domain.AgentRunRequest(UUID.randomUUID(), TENANT_ID,
                            agent, model, principal, "测试", java.util.List.of());
                    assertThat(context.getBean(AgentRuntime.class).run(request).status())
                            .isEqualTo(com.cmagent.core.domain.RunStatus.DENIED);
                    assertThat(reads).hasValue(0);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ToolGatewayConfiguration {
        @Bean
        /**
         * 验证或支持 {@code toolInvocationGateway} 所描述的测试场景。
         */
        ToolInvocationGateway toolInvocationGateway() {
            return request -> ToolInvocationResult.succeeded("测试结果");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RepositoryConfiguration {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.cmagent.core.repository.RuntimeExecutionModeRepository.class)
        com.cmagent.core.repository.RuntimeExecutionModeRepository modes() {
            // 配置测试不读取真实 Run/TEST 仓储；排空与旧实例隔离另有实际仓储合同。
            return new com.cmagent.server.store.InMemoryRuntimeExecutionModeRepository(() -> false);
        }
        @Bean
        ModelConfigRepository modelConfigRepository() {
            return mock(ModelConfigRepository.class);
        }

        @Bean
        RuntimeCheckpointRepository runtimeCheckpointRepository() {
            return mock(RuntimeCheckpointRepository.class);
        }
    }

}
