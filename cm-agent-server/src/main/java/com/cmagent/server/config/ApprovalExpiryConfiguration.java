package com.cmagent.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 由 Spring 管理调度器生命周期；关闭时扫描器立即返回，不创建自管线程。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ApprovalExpiryProperties.class)
public class ApprovalExpiryConfiguration {
}
