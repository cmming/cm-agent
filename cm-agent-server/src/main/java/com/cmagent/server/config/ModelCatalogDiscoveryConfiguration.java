package com.cmagent.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配模型目录发现的独立网络安全配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelCatalogDiscoveryProperties.class)
public class ModelCatalogDiscoveryConfiguration {
}
