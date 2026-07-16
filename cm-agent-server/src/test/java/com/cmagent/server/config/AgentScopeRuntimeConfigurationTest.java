package com.cmagent.server.config;

import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeRuntimeConfigurationTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentScopeRuntimeConfiguration.class, ToolGatewayConfiguration.class);

    @Test
    void enabledConfigurationProvidesRealRuntime() {
        contextRunner.withPropertyValues(
                        "cm-agent.fake-runtime-enabled=false",
                        "cm-agent.agentscope.enabled=true",
                        "cm-agent.agentscope.credentials[0].tenant-id=" + TENANT_ID,
                        "cm-agent.agentscope.credentials[0].model-config-id=" + MODEL_ID,
                        "cm-agent.agentscope.credentials[0].api-key=unit-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class);
                });
    }

    @Test
    void fakeAndRealRuntimeCannotBeEnabledTogether() {
        contextRunner.withPropertyValues(
                        "cm-agent.fake-runtime-enabled=true",
                        "cm-agent.agentscope.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("AgentScope 真实运行时与 fake runtime 不能同时启用"));
    }

    @Test
    void customCredentialProviderReplacesExternalCredentialMapping() {
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
    void missingCredentialOnlyReportsControlledMessage() {
        contextRunner.withPropertyValues("cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelCredentialProvider provider = context.getBean(ModelCredentialProvider.class);
                    assertThatThrownBy(() -> provider.resolve(TENANT_ID, MODEL_ID))
                            .hasMessage("模型凭据不可用")
                            .hasMessageNotContaining("api-key");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ToolGatewayConfiguration {
        @Bean
        ToolInvocationGateway toolInvocationGateway() {
            return request -> ToolInvocationResult.succeeded("测试结果");
        }
    }

}
