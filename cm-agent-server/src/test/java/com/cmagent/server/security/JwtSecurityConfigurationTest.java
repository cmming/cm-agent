package com.cmagent.server.security;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityConfigurationTest {
    private static final String STRONG_TEST_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecurityConfiguration.class);

    private final ApplicationContextRunner serverContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    JwtSecurityConfiguration.class,
                    BootstrapAdminConfiguration.class,
                    BootstrapAdminProperties.class,
                    BootstrapAdminPropertiesBindingConfiguration.class
            )
            .withPropertyValues("cm-agent.security.jwt-secret=" + STRONG_TEST_SECRET);

    @Test
    void failsWhenSecretMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
        });
    }

    @Test
    void rejectsFormerFallbackFlagForLocalProfile() {
        contextRunner
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
                });
    }

    @Test
    void rejectsFormerFallbackFlagForTestProfile() {
        contextRunner
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
                });
    }

    @Test
    void profileValidatorRejectsMixedProductionAndPostgresProfilesBeforeAcceptingConfiguredSecret() {
        profileContextRunner()
                .withPropertyValues("spring.profiles.active=production,postgres")
                .withPropertyValues("cm-agent.security.jwt-secret=" + STRONG_TEST_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                });
    }

    @Test
    void profileValidatorRejectsMixedSupabaseAndMysqlProfilesBeforeAcceptingConfiguredSecret() {
        profileContextRunner()
                .withPropertyValues("spring.profiles.active=supabase,mysql")
                .withPropertyValues("cm-agent.security.jwt-secret=" + STRONG_TEST_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                });
    }

    @Test
    void rejectsFormerFallbackFlagWhenProfileIsMissing() {
        contextRunner.withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
                });
    }

    @Test
    void rejectsFormerFallbackFlagForProductionProfile() {
        contextRunner
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
                });
    }

    @Test
    void rejectsMixedProductionAndTestProfilesCaseInsensitivelyAcrossDefaultLocales() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            profileContextRunner()
                    .withPropertyValues("cm-agent.security.jwt-secret=" + STRONG_TEST_SECRET)
                    .withPropertyValues("spring.profiles.active=PRODUCTION,test")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .isInstanceOf(BeanCreationException.class)
                                .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                    });
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void requiresExternalSecretInProductionProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
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

    @Test
    void rejectsBootstrapAdminForUppercaseProductionProfileAcrossDefaultLocales() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            serverContextRunner
                    .withPropertyValues("spring.profiles.active=PRODUCTION")
                    .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=true")
                    .withPropertyValues("cm-agent.security.bootstrap-admin-password=local-only-password")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("production/prod profile 禁止启用 bootstrap admin");
                    });
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    private ApplicationContextRunner profileContextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(JwtSecurityConfiguration.class, ProfileSafetyValidator.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class BootstrapAdminPropertiesBindingConfiguration {
    }
}
