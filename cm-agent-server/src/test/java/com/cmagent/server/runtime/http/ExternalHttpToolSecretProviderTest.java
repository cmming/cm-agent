package com.cmagent.server.runtime.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalHttpToolSecretProviderTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String SECRET_REF = "secret/integration/api-key";
    private static final String SECRET_VALUE = "仅用于单元测试的敏感值";

    @Test
    void propertiesUseSecureDefaults() {
        HttpToolProperties properties = new HttpToolProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isAllowHttp()).isFalse();
        assertThat(properties.getAllowedHosts()).isEmpty();
        assertThat(properties.getSecrets()).isEmpty();
        assertThat(properties.getMinTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.getMaxTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getMaxResponseBytes()).isEqualTo(262_144);
        assertThat(properties.getMaxRedirects()).isEqualTo(3);
    }

    @Test
    void propertiesRejectConfigurationThatWidensAbsoluteSafetyBounds() {
        HttpToolProperties properties = new HttpToolProperties();

        assertThatThrownBy(() -> properties.setMinTimeout(Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxTimeout(Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxResponseBytes(262_145))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxRedirects(4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesOnlyExactTenantAndSecretReferenceCompositeKey() {
        HttpToolProperties properties = new HttpToolProperties();
        properties.setSecrets(Map.of(
                ExternalHttpToolSecretProvider.compositeKey(TENANT_ID, SECRET_REF), SECRET_VALUE
        ));
        ExternalHttpToolSecretProvider provider = new ExternalHttpToolSecretProvider(properties);

        assertThat(provider.resolve(TENANT_ID, SECRET_REF)).contains(SECRET_VALUE);
        assertThat(provider.resolve(OTHER_TENANT_ID, SECRET_REF)).isEmpty();
        assertThat(provider.resolve(TENANT_ID, "secret/integration/other")).isEmpty();
    }

    @Test
    void propertiesAndProviderToStringNeverExposeSecretValue() {
        HttpToolProperties properties = new HttpToolProperties();
        properties.setSecrets(Map.of(
                ExternalHttpToolSecretProvider.compositeKey(TENANT_ID, SECRET_REF), SECRET_VALUE
        ));
        ExternalHttpToolSecretProvider provider = new ExternalHttpToolSecretProvider(properties);

        assertThat(properties.toString()).doesNotContain(SECRET_VALUE);
        assertThat(provider.toString()).doesNotContain(SECRET_VALUE);
    }

    @Test
    void runtimeConfigurationBindsExternalPropertiesAndProvidesOverridableDefaultProvider() {
        ApplicationContextRunner defaultRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpToolRuntimeConfiguration.class))
                .withPropertyValues(
                        "cm-agent.http-tools.enabled=true",
                        "cm-agent.http-tools.allowed-hosts[0]=api.example.com",
                        "cm-agent.http-tools.secrets[" +
                                ExternalHttpToolSecretProvider.compositeKey(TENANT_ID, SECRET_REF) + "]=" +
                                SECRET_VALUE
                );

        defaultRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HttpToolSecretProvider.class);
            assertThat(context.getBean(HttpToolSecretProvider.class).resolve(TENANT_ID, SECRET_REF))
                    .contains(SECRET_VALUE);
            assertThat(context.getBean(HttpToolProperties.class).toString()).doesNotContain(SECRET_VALUE);
        });

        defaultRunner.withUserConfiguration(CustomSecretProviderConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HttpToolSecretProvider.class);
            assertThat(context.getBean(HttpToolSecretProvider.class).resolve(TENANT_ID, SECRET_REF))
                    .contains("custom");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSecretProviderConfiguration {
        @Bean
        HttpToolSecretProvider customHttpToolSecretProvider() {
            return (tenantId, secretRef) -> java.util.Optional.of("custom");
        }
    }
}
