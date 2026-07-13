package com.cmagent.server.config;

import com.cmagent.server.security.BootstrapAdminConfiguration;
import com.cmagent.server.security.BootstrapAdminProperties;
import com.cmagent.server.security.JwtSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigurationTest {
    private static final String LOCAL_JWT_SECRET = "cm-agent-local-dev-jwt-secret-with-at-least-32-bytes-2026";
    private static final String LOCAL_ADMIN_PASSWORD = "cm-agent-local-dev-password-only";
    private static final String TEST_JWT_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";
    private static final String TEST_ADMIN_PASSWORD = "cm-agent-test-password-only";
    private static final String VM_JWT_SECRET = "cm-agent-vm-profile-jwt-secret-with-at-least-32-bytes-2026";
    private static final String POSTGRES_JDBC_URL = "jdbc:postgresql://192.168.0.66:5432/cm_agent";
    private static final String MYSQL_JDBC_URL = "jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private final ApplicationContextRunner productionGuardContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ServerRepositoryConfiguration.class,
                    JwtSecurityConfiguration.class,
                    BootstrapAdminConfiguration.class,
                    BootstrapAdminProperties.class
            )
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfigurationActivatesLocalProfileFromCmAgentProfileSelector() {
        contextRunner.run(context -> assertLocalProfileLoaded(context.getEnvironment()));
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
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(LOCAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(LOCAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.config.bootstrap-admin-password"))
                            .isEqualTo(LOCAL_ADMIN_PASSWORD);
                });
    }

    @Test
    void productionProfileProvidesJdbcConfigurationVariables() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context.getEnvironment().getProperty("cm-agent.config.persistence-mode"))
                        .isEqualTo("jdbc"));
    }

    @Test
    void legacyProfileSelectorActivatesProductionInsteadOfFallingBackToLocal() {
        contextRunner
                .withPropertyValues("CM_AGENT_PROFILE=production")
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
                    jwt-secret: external-production-jwt-secret-with-at-least-32-bytes
                    jdbc-url: jdbc:postgresql://external-host:5432/cm_agent
                    jdbc-username: external-user
                    jdbc-password: external-password
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
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).contains("prod", "production");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                });
    }

    @Test
    void postgresProfileLoadsVirtualMachineJdbcConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=postgres")
                .run(context -> assertPostgresProfileLoaded(context.getEnvironment()));
    }

    @Test
    void mysqlProfileLoadsVirtualMachineJdbcConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=mysql")
                .run(context -> assertMysqlProfileLoaded(context.getEnvironment()));
    }

    @Test
    void productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    @Test
    void productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void uppercaseProductionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=PRODUCTION")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void rejectsMixedProductionAndTestProfilesEvenWhenBootstrapAdminDisabled() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=production,test")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod profile 禁止与 test profile 同时启用");
                });
    }

    @Test
    void supabaseProfileLoadsJdbcDefaultsFromConfigData() {
        contextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).containsExactly("supabase");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                    assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                    assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class))
                            .isFalse();
                    assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class))
                            .isFalse();
                });
    }

    @Test
    void supabaseProfileRejectsMissingJdbcUrlWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
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
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
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
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
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
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止与 test profile 同时启用");
                });
    }

    private static void assertPostgresProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "postgres", POSTGRES_JDBC_URL, "cmagent", "org.postgresql.Driver");
    }

    private static void assertMysqlProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "mysql", MYSQL_JDBC_URL, "root", "com.mysql.cj.jdbc.Driver");
    }

    private static void assertVirtualMachineProfileLoaded(
            Environment environment, String profile, String jdbcUrl, String username, String driverClassName) {
        assertThat(environment.getActiveProfiles()).containsExactly(profile);
        assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(VM_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(VM_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.config.jdbc-url")).isEqualTo(jdbcUrl);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.url")).isEqualTo(jdbcUrl);
        assertThat(environment.getProperty("cm-agent.config.jdbc-username")).isEqualTo(username);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.username")).isEqualTo(username);
        assertThat(environment.getProperty("cm-agent.config.jdbc-password")).isEqualTo("cmagent");
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.password")).isEqualTo("cmagent");
        assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.config.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.config.bootstrap-admin-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isFalse();
    }

    private static void assertLocalProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("local");
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(LOCAL_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEqualTo(LOCAL_ADMIN_PASSWORD);
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("本地管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("memory");
    }

    private static void assertTestProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEqualTo(TEST_ADMIN_PASSWORD);
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("测试管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
    }
}
