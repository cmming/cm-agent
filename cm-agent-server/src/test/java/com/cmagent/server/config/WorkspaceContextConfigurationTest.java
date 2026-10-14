package com.cmagent.server.config;

import com.cmagent.core.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅配置装配，不读取参考文件；验证旧自定义Runtime不能静默忽略启用的资料。 */
class WorkspaceContextConfigurationTest {
    @Test void 默认关闭不要求旧Runtime支持参考快照() {
        var runtime = mock(AgentRuntime.class);
        runner(runtime).run(context -> assertThat(context).hasNotFailed());
    }
    @Test void 显式启用但能力缺失拒绝启动() {
        var runtime = mock(AgentRuntime.class);
        runner(runtime).withPropertyValues("cm-agent.workspace-context.enabled=true", "cm-agent.workspace-context.root-directory=private-placeholder")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void 支持能力仍不能通过超出硬预算的配置() {
        var runtime = mock(AgentRuntime.class); when(runtime.supportsWorkspaceContext()).thenReturn(true);
        runner(runtime).withPropertyValues("cm-agent.workspace-context.max-total-bytes=65537")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void 配置拒绝跨目录路径别名和重复身份映射() {
        var policy = new WorkspaceContextProperties();
        var tenant = java.util.UUID.randomUUID(); var agent = java.util.UUID.randomUUID();
        policy.setBindings(java.util.List.of(new WorkspaceContextProperties.Binding(tenant, agent, "../escape", java.util.List.of("guide.md"))));
        assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class);
        var binding = new WorkspaceContextProperties.Binding(tenant, agent, "reference", java.util.List.of("guide.md"));
        policy.setBindings(java.util.List.of(binding, binding));
        assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class);
    }
    private ApplicationContextRunner runner(AgentRuntime runtime) {
        return new ApplicationContextRunner().withUserConfiguration(WorkspaceContextConfiguration.class).withBean(AgentRuntime.class, () -> runtime);
    }
}
