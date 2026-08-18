package com.cmagent.server.config;

import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.repository.ModelConfigRepository;
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
import static org.mockito.Mockito.mock;

class ApplicationProfileConfigurationTest {
    private static final String EXTERNAL_LOCAL_JWT_SECRET = "external-local-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_LOCAL_ADMIN_PASSWORD = "external-local-admin-password";
    private static final String EXTERNAL_TEST_JWT_SECRET = "external-test-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_JWT_SECRET = "external-strict-jwt-secret-with-at-least-32-bytes";
    private static final String EXTERNAL_JDBC_URL = "jdbc:postgresql://external-host:5432/cm_agent";
    private static final String EXTERNAL_JDBC_USERNAME = "external-user";
    private static final String EXTERNAL_JDBC_PASSWORD = "external-password";

    /**
     * 验证或支持 {@code externalConfigProperties} 所描述的测试场景。
     *
     * @param profileSelector 测试辅助方法使用的 profileSelector 参数
     */
    private static String[] externalConfigProperties(String profileSelector) {
        return new String[]{
                profileSelector,
                "cm-agent.config.external-jwt-secret=" + EXTERNAL_JWT_SECRET,
                "cm-agent.config.external-jdbc-url=" + EXTERNAL_JDBC_URL,
                "cm-agent.config.external-jdbc-username=" + EXTERNAL_JDBC_USERNAME,
                "cm-agent.config.external-jdbc-password=" + EXTERNAL_JDBC_PASSWORD,
                "cm-agent.model-credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
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

    private final ApplicationContextRunner realRuntimeProfileContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AgentScopeRuntimeConfiguration.class,
                    ModelCredentialEncryptionConfiguration.class,
                    ProfileSafetyValidator.class)
            .withBean(ToolInvocationGateway.class,
                    () -> request -> ToolInvocationResult.succeeded("测试结果"))
            .withBean(ModelConfigRepository.class, () -> mock(ModelConfigRepository.class))
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    /**
     * 验证或支持 {@code defaultConfigurationRejectsStartupWithoutExplicitProfile} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code explicitSpringProfileArgumentCanActivateLocalProfileAndLoadLocalConfiguration} 所描述的测试场景。
     */
    void explicitSpringProfileArgumentCanActivateLocalProfileAndLoadLocalConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> assertLocalProfileLoaded(context.getEnvironment()));
    }

    @Test
    /**
     * 验证或支持 {@code explicitSpringProfileArgumentCanActivateTestProfileAndLoadTestConfiguration} 所描述的测试场景。
     */
    void explicitSpringProfileArgumentCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    @Test
    /**
     * 验证或支持 {@code localProfileVariablesOverrideCommonConfiguration} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code productionProfileProvidesJdbcConfigurationVariables} 所描述的测试场景。
     */
    void productionProfileProvidesJdbcConfigurationVariables() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=production"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("cm-agent.agentscope.enabled", Boolean.class)).isTrue();
                    assertThat(environment.getProperty("cm-agent.http-tools.enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("cm-agent.http-tools.allow-http", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(EXTERNAL_JWT_SECRET);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-url")).isEqualTo(EXTERNAL_JDBC_URL);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-username"))
                            .isEqualTo(EXTERNAL_JDBC_USERNAME);
                    assertThat(environment.getProperty("cm-agent.config.jdbc-password"))
                            .isEqualTo(EXTERNAL_JDBC_PASSWORD);
                });
    }

    @Test
    /**
     * 验证或支持 {@code localProfileRejectsFakeAndAgentScopeRuntimeTogether} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code legacyProfileSelectorActivatesProductionInsteadOfFallingBackToLocal} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code externalProductionConfigDataMapsControlledConfigurationVariables} 所描述的测试场景。
     *
     * @param configDirectory 测试辅助方法使用的 configDirectory 参数
     */
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
    /**
     * 验证或支持 {@code prodProfileActivatesProductionVariableGroup} 所描述的测试场景。
     */
    void prodProfileActivatesProductionVariableGroup() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=prod"))
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).contains("prod", "production");
                    assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    /**
     * 验证或支持 {@code strictProfileProvidesRealAgentScopeRuntimeWithoutTestRuntime} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
    void strictProfileProvidesRealAgentScopeRuntimeWithoutTestRuntime(String profile) {
        realRuntimeProfileContextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=" + profile))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment()
                            .getProperty("cm-agent.agentscope.enabled", Boolean.class)).isTrue();
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "supabase"})
    /**
     * 验证或支持 {@code strictProfileRejectsAllowHttpEvenWhenHttpExecutorIsDisabled} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
    void strictProfileRejectsAllowHttpEvenWhenHttpExecutorIsDisabled(String profile) {
        productionGuardContextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=" + profile))
                .withPropertyValues(
                        "cm-agent.http-tools.enabled=false",
                        "cm-agent.http-tools.allow-http=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 HTTP 明文协议");
                });
    }

    @Test
    /**
     * 验证或支持 {@code postgresProfileLoadsJdbcConfigurationWithExternalPlaceholders} 所描述的测试场景。
     */
    void postgresProfileLoadsJdbcConfigurationWithExternalPlaceholders() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=postgres"))
                .run(context -> assertPostgresProfileLoaded(context.getEnvironment()));
    }

    @Test
    /**
     * 验证或支持 {@code mysqlProfileLoadsJdbcConfigurationWithExternalPlaceholders} 所描述的测试场景。
     */
    void mysqlProfileLoadsJdbcConfigurationWithExternalPlaceholders() {
        contextRunner
                .withPropertyValues(externalConfigProperties("spring.profiles.active=mysql"))
                .run(context -> assertMysqlProfileLoaded(context.getEnvironment()));
    }

    @Test
    /**
     * 验证或支持 {@code productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code uppercaseProductionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists} 所描述的测试场景。
     */
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
    /**
     * 验证 {@code MixedProductionAndTestProfilesEvenWhenBootstrapAdminDisabled} 异常场景会被正确拒绝。
     */
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
    /**
     * 验证或支持 {@code productionProfileRejectsFakeRuntimeWhenJdbcAndJwtAreConfigured} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code strictProfilesRejectMemoryPersistenceMode} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
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
    /**
     * 验证或支持 {@code strictProfilesRejectBootstrapAdmin} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
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
    /**
     * 验证或支持 {@code strictProfilesRejectDevelopmentJwtFallback} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
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
    /**
     * 验证或支持 {@code strictProfilesRejectFakeRuntime} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
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
    /**
     * 验证或支持 {@code strictProfileRejectsStartupWhenRealRuntimeBeanIsMissing} 所描述的测试场景。
     */
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

    /**
     * 验证或支持 {@code strictProfileContextRunner} 所描述的测试场景。
     *
     * @param profile 测试辅助方法使用的 profile 参数
     */
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
    /**
     * 验证 {@code MixedProductionAndLocalProfilesBeforeLoadingMemoryDefaults} 异常场景会被正确拒绝。
     */
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
    /**
     * 验证或支持 {@code supabaseProfileLoadsJdbcDefaultsFromConfigData} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code supabaseProfileRejectsMissingJdbcUrlWhenJwtSecretExists} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code supabaseProfileRejectsMemoryPersistenceModeWhenJwtSecretExists} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code supabaseProfileRejectsBootstrapAdminWhenJdbcConfigured} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code supabaseProfileRejectsTestProfileMixingWhenJdbcConfigured} 所描述的测试场景。
     */
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

    /**
     * 验证或支持 {@code assertPostgresProfileLoaded} 所描述的测试场景。
     *
     * @param environment 测试辅助方法使用的 environment 参数
     */
    private static void assertPostgresProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "postgres", "org.postgresql.Driver");
    }

    /**
     * 验证或支持 {@code assertMysqlProfileLoaded} 所描述的测试场景。
     *
     * @param environment 测试辅助方法使用的 environment 参数
     */
    private static void assertMysqlProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(environment, "mysql", "com.mysql.cj.jdbc.Driver");
    }

    /**
     * 验证或支持 {@code assertVirtualMachineProfileLoaded} 所描述的测试场景。
     *
     * @param environment 测试辅助方法使用的 environment 参数
     * @param profile 测试辅助方法使用的 profile 参数
     * @param driverClassName 测试辅助方法使用的 driverClassName 参数
     */
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

    /**
     * 验证或支持 {@code assertLocalProfileLoaded} 所描述的测试场景。
     *
     * @param environment 测试辅助方法使用的 environment 参数
     */
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

    /**
     * 验证或支持 {@code assertTestProfileLoaded} 所描述的测试场景。
     *
     * @param environment 测试辅助方法使用的 environment 参数
     */
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
        assertThat(environment.getProperty("cm-agent.http-tools.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.http-tools.allow-http", Boolean.class)).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestAgentRuntimeConfiguration {
        @Bean
        /**
         * 验证或支持 {@code agentRuntime} 所描述的测试场景。
         */
        AgentRuntime agentRuntime() {
            return request -> null;
        }
    }
}
