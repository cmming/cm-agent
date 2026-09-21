package com.cmagent.server.config;

import com.cmagent.server.service.SkillPackageParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 技能功能的基础配置，后续任务在此装配存储、管理和运行时服务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfiguration {

    /**
     * 创建无状态技能包解析器，并在服务启动期验证全部限制。
     *
     * @param properties 技能配置属性
     * @return 可复用的内存技能包解析器
     */
    @Bean
    SkillPackageParser skillPackageParser(SkillProperties properties) {
        properties.validate();
        return new SkillPackageParser(properties.getAllowedResourceTypes());
    }
}
