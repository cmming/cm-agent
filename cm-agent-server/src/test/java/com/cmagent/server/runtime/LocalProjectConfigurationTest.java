package com.cmagent.server.runtime;

import com.cmagent.server.config.LocalProjectProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** 只核验装配开关与失败关闭条件，不初始化原生DLL、数据库、项目状态卷或真实Provider。 */
class LocalProjectConfigurationTest {
    @TempDir Path directory;

    @Test void 默认关闭不要求节点配置且不装配任何原生项目组件() {
        var properties = new LocalProjectProperties(); assertThat(properties.isEnabled()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        new ApplicationContextRunner().withUserConfiguration(LocalProjectConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LocalProjectSelections.class).doesNotHaveBean(LocalProjectStateLock.class)
                    .doesNotHaveBean(LocalProjectService.class).doesNotHaveBean(LocalProjectTools.class);
        });
    }

    @Test void 已启用但缺失固定配置时拒绝并不回显私有路径() {
        var properties = new LocalProjectProperties(); properties.setEnabled(true); properties.setStateDirectory(directory.toString());
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(directory.toString());
        for (Consumer<LocalProjectProperties> invalid : List.<Consumer<LocalProjectProperties>>of(
                policy -> policy.setSelectionTtl(Duration.ZERO), policy -> policy.setSelectionTtl(Duration.ofMinutes(6)),
                policy -> policy.setAllowedOrigins(List.of("http://example.invalid")), policy -> policy.setAllowedOrigins(List.of("http://localhost/")),
                policy -> policy.setNodeId(""), policy -> policy.setStateDirectory("relative-state"))) {
            var policy = properties(); invalid.accept(policy);
            assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(directory.toString());
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 完整显式本机配置通过部署校验() {
        assertThatCode(() -> LocalProjectConfiguration.validateDeployment(properties(), environment())).doesNotThrowAnyException();
        assertThatCode(() -> LocalProjectConfiguration.validateDeployment(properties(), environment().withProperty("cm-agent.agentscope.execution-mode","harness"))).doesNotThrowAnyException();
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void 显式允许非回环后才能使用通配监听() {
        var policy = properties(); policy.setAllowNonLoopback(true);
        var environment = environment().withProperty("server.address", "0.0.0.0");
        assertThatCode(() -> LocalProjectConfiguration.validateDeployment(policy, environment)).doesNotThrowAnyException();
    }

    @ParameterizedTest @MethodSource("unsafeDeployment") @EnabledOnOs(OS.WINDOWS)
    void 不完整部署不能悄悄降级(String key, String value) {
        assertThatThrownBy(() -> LocalProjectConfiguration.validateDeployment(properties(), environment().withProperty(key, value)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("本地项目仅支持Windows");
    }

    @Test void 生产或缺失本机profile始终拒绝() {
        for (String[] profiles : List.of(new String[]{"prod"}, new String[]{"local", "production"}, new String[]{"test", "supabase"}, new String[]{})) {
            var environment = environment(); environment.setActiveProfiles(profiles);
            assertThatThrownBy(() -> LocalProjectConfiguration.validateDeployment(properties(), environment)).isInstanceOf(IllegalStateException.class);
        }
        var policy = properties(); policy.setAllowNonLoopback(true);
        var production = environment().withProperty("server.address", "0.0.0.0");
        production.setActiveProfiles("prod");
        assertThatThrownBy(() -> LocalProjectConfiguration.validateDeployment(policy, production))
                .isInstanceOf(IllegalStateException.class);
    }

    private LocalProjectProperties properties() {
        var value = new LocalProjectProperties(); value.setEnabled(true); value.setNodeId("test-node");
        value.setStateDirectory(directory.toAbsolutePath().toString()); value.setAllowedOrigins(List.of("http://127.0.0.1:19080")); return value;
    }

    private static MockEnvironment environment() {
        var value = new MockEnvironment().withProperty("cm-agent.persistence.mode", "jdbc").withProperty("server.address", "127.0.0.1")
                .withProperty("server.forward-headers-strategy", "none").withProperty("cm-agent.agentscope.enabled", "true")
                .withProperty("cm-agent.agentscope.permission-enabled", "true").withProperty("cm-agent.agentscope.execution-mode", "react")
                .withProperty("cm-agent.agentscope.studio.enabled", "false");
        value.setActiveProfiles("test"); return value;
    }

    static Stream<Arguments> unsafeDeployment() {
        return Stream.of(Arguments.of("cm-agent.persistence.mode", "memory"), Arguments.of("server.address", "0.0.0.0"),
                Arguments.of("server.address", ""), Arguments.of("server.forward-headers-strategy", "framework"),
                Arguments.of("cm-agent.agentscope.enabled", "false"), Arguments.of("cm-agent.agentscope.permission-enabled", "false"),
                Arguments.of("cm-agent.agentscope.execution-mode", "unknown"), Arguments.of("cm-agent.agentscope.studio.enabled", "true"));
    }
}
