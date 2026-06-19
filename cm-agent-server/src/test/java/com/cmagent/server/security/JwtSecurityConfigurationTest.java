package com.cmagent.server.security;

import com.cmagent.server.CmAgentServerApplication;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityConfigurationTest {
    private static final String STRONG_TEST_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecurityConfiguration.class);

    private final WebApplicationContextRunner serverContextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(CmAgentServerApplication.class)
            .withPropertyValues("cm-agent.security.jwt-secret=" + STRONG_TEST_SECRET);

    @Test
    void failsWhenSecretMissingWithoutExplicitFallbackFlag() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
        });
    }

    @Test
    void allowsFallbackOnlyForLocalOrTestProfileWithExplicitOptIn() {
        contextRunner
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecretKey.class);
                });
    }

    @Test
    void rejectsMixedProductionAndTestProfilesEvenWithOptInFlag() {
        contextRunner
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .withPropertyValues("spring.profiles.active=test,production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    @Test
    void rejectsFallbackWhenProfileIsMissingEvenWithOptInFlag() {
        contextRunner.withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    @Test
    void requiresExternalSecretInProductionProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    @Test
    void rejectsWeakConfiguredSecret() {
        contextRunner
                .withPropertyValues("cm-agent.security.jwt-secret=short-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasRootCauseInstanceOf(WeakKeyException.class);
                });
    }

    @Test
    void rejectsBootstrapAdminWhenEnabledWithoutPassword() {
        serverContextRunner
                .withPropertyValues("spring.profiles.active=test")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("启用 bootstrap admin 时必须配置 cm-agent.security.bootstrap-admin-password");
                });
    }

    @Test
    void rejectsBootstrapAdminInProductionEvenWithPassword() {
        serverContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=true")
                .withPropertyValues("cm-agent.security.bootstrap-admin-password=local-only-password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod profile 禁止启用 bootstrap admin");
                });
    }
}
