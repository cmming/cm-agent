package com.cmagent.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.BeanCreationException;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecurityConfiguration.class);

    @Test
    void usesLocalFallbackWhenNoProfileIsActiveAndSecretMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SecretKey.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void requiresExternalSecretInProductionProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }
}
