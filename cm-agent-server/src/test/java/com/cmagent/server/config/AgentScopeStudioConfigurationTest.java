package com.cmagent.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeStudioConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentScopeStudioConfiguration.class)
            .withPropertyValues("spring.profiles.active=local");

    @Test
    /** 验证启用 Studio 时会使用绑定后的实例级配置初始化调试连接。 */
    void enabledConfigurationInitializesStudioRuntime() {
        AtomicReference<AgentScopeStudioProperties> initialized = new AtomicReference<>();

        contextRunner
                .withBean(AgentScopeStudioRuntime.class, () -> initialized::set)
                .withPropertyValues(
                        "cm-agent.agentscope.studio.enabled=true",
                        "cm-agent.agentscope.studio.url=http://localhost:8000",
                        "cm-agent.agentscope.studio.project=cm-agent-test",
                        "cm-agent.agentscope.studio.run-name=cm-agent-test-instance")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(initialized.get()).isNotNull();
                    assertThat(initialized.get().getProject()).isEqualTo("cm-agent-test");
                    assertThat(initialized.get().getRunName()).isEqualTo("cm-agent-test-instance");
                });
    }

    @Test
    /** 验证包含认证信息的 Studio 地址会在建立外部连接前被拒绝。 */
    void rejectsStudioUrlContainingUserInfo() {
        contextRunner
                .withBean(AgentScopeStudioRuntime.class, () -> properties -> {
                })
                .withPropertyValues(
                        "cm-agent.agentscope.studio.enabled=true",
                        "cm-agent.agentscope.studio.url=http://user:password@localhost:8000")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("AgentScope Studio 地址必须是 HTTP(S) 绝对地址"));
    }
}
