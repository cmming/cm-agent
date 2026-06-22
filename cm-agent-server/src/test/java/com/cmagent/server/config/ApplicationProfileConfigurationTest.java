package com.cmagent.server.config;

import com.cmagent.server.CmAgentServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigurationTest {
    private static final String TEST_JWT_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";
    private static final String TEST_ADMIN_PASSWORD = "cm-agent-test-password-only";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(CmAgentServerApplication.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfigurationActivatesLocalProfileFromCmAgentProfileSelector() {
        contextRunner.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getActiveProfiles()).containsExactly("local");
            assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isTrue();
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEmpty();
        });
    }

    @Test
    void cmAgentProfileCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("CM_AGENT_PROFILE=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    @Test
    void explicitSpringProfileArgumentCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    @Test
    void productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded() {
        webContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    private static void assertTestProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEqualTo(TEST_ADMIN_PASSWORD);
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("测试管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
    }
}
