package com.cmagent.server.runtime.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(HttpToolProperties.class)
/** 动态 HTTP 工具运行时的自动配置入口。 */
public class HttpToolRuntimeConfiguration {

    /**
     * 创建默认 HTTP 工具 secret 提供者。
     *
     * @param properties HTTP 工具运行时配置
     * @return 外部 secret 提供者
     */
    @Bean
    @ConditionalOnMissingBean(HttpToolSecretProvider.class)
    HttpToolSecretProvider externalHttpToolSecretProvider(HttpToolProperties properties) {
        return new ExternalHttpToolSecretProvider(properties);
    }
}
