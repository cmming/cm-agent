package com.cmagent.starter;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
