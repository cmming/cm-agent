package com.cmagent.server.runtime.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(HttpToolProperties.class)
public class HttpToolRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(HttpToolSecretProvider.class)
    HttpToolSecretProvider externalHttpToolSecretProvider(HttpToolProperties properties) {
        return new ExternalHttpToolSecretProvider(properties);
    }
}
