package com.cmagent.server.config;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.security.BootstrapAdminConfiguration;
import com.cmagent.server.security.BootstrapAdminProperties;
import com.cmagent.server.security.JwtSecurityConfiguration;
import com.cmagent.server.security.ProfileSafetyValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigurationTest {
    private static final String EXTERNAL_LOCAL_JWT_SECRET = "external-local-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_LOCAL_ADMIN_PASSWORD = "external-local-admin-password";
    private static final String EXTERNAL_TEST_JWT_SECRET = "external-test-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_JWT_SECRET = "external-strict-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_JDBC_URL = "jdbc:postgresql://external-host:5432/cm_agent";
    private static final String EXTERNAL_JDBC_USERNAME = "external-user";
    private static final String EXTERNAL_JDBC_PASSWORD = "external-password";

    private static String[] externalConfigProperties(String profileSelector) {
        return new String[]{
                profileSelector,
                "cm-agent.config.external-jwt-secret=" + EXTERNAL_JWT_SECRET,
                "cm-agent.config.external-jdbc-url=" + EXTERNAL_JDBC_URL,
                "cm-agent.config.external-jdbc-username=" + EXTERNAL_JDBC_USERNAME,
                "cm-agent.config.external-jdbc-password=" + EXTERNAL_JDBC_PASSWORD
        };
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private final ApplicationContextRunner productionGuardContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ServerRepositoryConfiguration.class,
                    JwtSecurityConfiguration.class,
                    BootstrapAdminConfiguration.class,
                    BootstrapAdminProperties.class,
                    ProfileSafetyValidator.class,
                    TestAgentRuntimeConfiguration.class
            )
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private final ApplicationContextRunner productionGuardWithoutRuntimeContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ServerRepositoryConfiguration.class,
                    JwtSecurityConfiguration.class,
                    BootstrapAdminConfiguration.class,
                    BootstrapAdminProperties.class,
                    ProfileSafetyValidator.class
            )
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfigurationRejectsStartupWithoutExplicitProfile() {
        contextRunner
                .withUserConfiguration(ProfileSafetyValidator.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("必须显式配置 spring.profiles.active 或 CM_AGENT_PROFILE");
                });
    }

    @Test
    void explicitSpringProfileArgumentCanActivateLocalProfileAndLoadLocalConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> assertLocalProfileLoaded(context.getEnvironment()));
    }

    @Test
    void explicitSpringProfileArgumentCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    @Test
    void localProfileVariablesOverrideCommonConfiguration() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "cm-agent.config.jwt-secret=" + EXTERNAL_LOCAL_JWT_SECRET,
                        "cm-agent.config.bootstrap-admin-password=" + EXTERNAL_LOCAL_ADMIN_PASSWORD
                )
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(EXTERNAL_LOCAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(EXTERNAL_LOCAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.config.bootstrap-admin-password"))
                            .isEqualTo(EXTERNAL_LOCAL_ADMIN_PASSWORD);
                });
    }

    @Test
    void productionProfileProvidesJdbcConfigurationVariables() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=production"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("cm-agent.agentscope.enabled", Boolean.class)).isTrue();
                    assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(EXTERNAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-url")).isEqualTo(EXTERNAL_JDBC_URL);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-username"))
                            .isEqualTo(EXTERNAL_JDBC_USERNAME);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-password"))
                            .isEqualTo(EXTERNAL_JDBC_PASSWORD);
                });
    }

    @Test
    void localProfileRejectsFakeAndAgentScopeRuntimeTogether() {
        contextRunner
                .withUserConfiguration(ProfileSafetyValidator.class, TestAgentRuntimeConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "cm-agent.fake-runtime-enabled=true",
                        "cm-agent.agentscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("AgentScope 真实运行时与 fake runtime 不能同时启用");
                });
    }

    @Test
    void legacyProfileSelectorActivatesProductionInsteadOfFallingBackToLocal() {
        contextRunner
                .withPropertyValues(externalConfigProperties("CM_AGENT_PROFILE=production"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).containsExactly("production");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                });
    }

    @Test
    void externalProductionConfigDataMapsControlledConfigurationVariables(@TempDir Path configDirectory)
            throws IOException {
        Files.writeString(configDirectory.resolve("application-production.yml"), """
                cm-agent:
                  config:
                    external-jwt-secret: external-production-jwt-secret-with-at-least-32-bytes
                    external-jdbc-url: jdbc:postgresql://external-host:5432/cm_agent
                    external-jdbc-username: external-user
                    external-jdbc-password: external-password
                    jdbc-driver-class-name: org.postgresql.Driver
                """);

        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=production",
                        "spring.config.additional-location=optional:" + configDirectory.toUri()
                )
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getProperty("cm-agent.security.jwt-secret"))
                            .isEqualTo("external-production-jwt-secret-with-at-least-32-bytes");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.url"))
                            .isEqualTo("jdbc:postgresql://external-host:5432/cm_agent");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.username")).isEqualTo("external-user");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.password")).isEqualTo("external-password");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                });
    }

    @Test
    void prodProfileActivatesProductionVariableGroup() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=prod"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).contains("prod", "production");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                });
    }

    @Test
    void postgresProfileLoadsJdbcConfigurationWithExternalPlaceholders() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=postgres"))
                .run(context -> assertPostgresProfileLoaded(context.getEnvironment()));
    }

    @Test
    void mysqlProfileLoadsJdbcConfigurationWithExternalPlaceholders() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=mysql"))
                .run(context -> assertMysqlProfileLoaded(context.getEnvironment()));
    }

    @Test
    void productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=")
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=" + EXTERNAL_JDBC_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("未配置 cm-agent.security.jwt-secret");
                });
    }

    @Test
    void productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void uppercaseProductionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=PRODUCTION")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void rejectsMixedProductionAndTestProfilesEvenWhenBootstrapAdminDisabled() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production,test")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                });
    }

    @Test
    void productionProfileRejectsFakeRuntimeWhenJdbcAndJwtAreConfigured() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.fake-runtime-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 fake runtime");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    void strictProfilesRejectMemoryPersistenceMode(String profile) {
        strictProfileContextRunner(profile)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须使用 jdbc 持久化模式");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    void strictProfilesRejectBootstrapAdmin(String profile) {
        strictProfileContextRunner(profile)
                .withPropertyValues(
                        "cm-agent.security.bootstrap-admin-enabled=true",
                        "cm-agent.security.bootstrap-admin-password=local-password"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 bootstrap admin");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    void strictProfilesRejectDevelopmentJwtFallback(String profile) {
        strictProfileContextRunner(profile)
                .withPropertyValues("cm-agent.security.allow-dev-jwt-fallback=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用开发 JWT 回退");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    void strictProfilesRejectFakeRuntime(String profile) {
        strictProfileContextRunner(profile)
                .withPropertyValues("cm-agent.fake-runtime-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 fake runtime");
                });
    }

    @Test
    void strictProfileRejectsStartupWhenRealRuntimeBeanIsMissing() {
        productionGuardWithoutRuntimeContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=" + EXTERNAL_JDBC_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须提供真实 AgentRuntime");
                });
    }

    private ApplicationContextRunner strictProfileContextRunner(String profile) {
        return productionGuardContextRunner
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET,
                        "cm-agent.persistence.mode=jdbc",
                        "cm-agent.persistence.jdbc.url=" + EXTERNAL_JDBC_URL
                );
    }

    @Test
    void rejectsMixedProductionAndLocalProfilesBeforeLoadingMemoryDefaults() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production,local")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                });
    }

    @Test
    void supabaseProfileLoadsJdbcDefaultsFromConfigData() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=supabase"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).containsExactly("supabase");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                    assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                    assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback")).isNull();
                    assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class))
                            .isFalse();
                });
    }

    @Test
    void supabaseProfileRejectsMissingJdbcUrlWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.jdbc.url=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
                });
    }

    @Test
    void supabaseProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void supabaseProfileRejectsBootstrapAdminWhenJdbcConfigured() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=true")
                .withPropertyValues("cm-agent.security.bootstrap-admin-password=local-password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 bootstrap admin");
                });
    }

    @Test
    void supabaseProfileRejectsTestProfileMixingWhenJdbcConfigured() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase,test")
                .withPropertyValues("cm-agent.security.jwt-secret=" + EXTERNAL_TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
                });
    }

    private static void assertPostgresProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "postgres", "org.postgresql.Driver");
    }

    private static void assertMysqlProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "mysql", "com.mysql.cj.jdbc.Driver");
    }

    private static void assertVirtualMachineProfileLoaded(
            Environment environment, String profile, String driverClassName) {
        assertThat(environment.getActiveProfiles()).containsExactly(profile);
        assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(EXTERNAL_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(EXTERNAL_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.config.jdbc-url")).isEqualTo(EXTERNAL_JDBC_URL);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.url")).isEqualTo(EXTERNAL_JDBC_URL);
        assertThat(environment.getProperty("cm-agent.config.jdbc-username")).isEqualTo(EXTERNAL_JDBC_USERNAME);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.username")).isEqualTo(EXTERNAL_JDBC_USERNAME);
        assertThat(environment.getProperty("cm-agent.config.jdbc-password")).isEqualTo(EXTERNAL_JDBC_PASSWORD);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.password")).isEqualTo(EXTERNAL_JDBC_PASSWORD);
        assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.config.bootstrap-admin-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.config.fake-runtime-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback")).isNull();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isFalse();
    }

    private static void assertLocalProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("local");
        assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isBlank();
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isBlank();
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback")).isNull();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isBlank();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("本地管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("memory");
    }

    private static void assertTestProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(EXTERNAL_TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(EXTERNAL_TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback")).isNull();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password"))
                .isEqualTo("cm-agent-test-password-only");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("测试管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestAgentRuntimeConfiguration {
        @Bean
        AgentRuntime agentRuntime() {
            return request -> null;
        }
    }
}
