package com.cmagent.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.jsonwebtoken.security.WeakKeyException;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecurityConfiguration.class);

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
}
