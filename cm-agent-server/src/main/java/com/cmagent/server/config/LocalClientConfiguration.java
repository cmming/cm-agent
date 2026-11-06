package com.cmagent.server.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 启用时固定本机客户端通道的部署身份并校验安全期限。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalClientProperties.class)
public class LocalClientConfiguration {
    /** @param properties 本机客户端配置 @return 启动期配置校验器 */
    @Bean
    InitializingBean localClientPropertiesValidator(LocalClientProperties properties) {
        return properties::validate;
    }
}
