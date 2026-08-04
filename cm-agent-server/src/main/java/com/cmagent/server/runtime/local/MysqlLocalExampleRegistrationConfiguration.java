package com.cmagent.server.runtime.local;

import com.cmagent.core.tool.ToolRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 在允许的 MySQL 调试 profile 中注册固定 LOCAL 执行器。
 */
@Configuration(proxyBeanMethods = false)
@Profile("mysql & !prod & !production & !supabase")
public class MysqlLocalExampleRegistrationConfiguration {

    @Bean
    /**
     * 向运行时注册 MySQL profile 使用的本地示例执行器。
     *
     * @param registry 本地工具执行器注册表
     * @param catalog 本地示例工具目录
     */
    InitializingBean registerMysqlLocalExamples(ToolRegistry registry, MysqlLocalExampleCatalog catalog) {
        return () -> catalog.list().forEach(example ->
                registry.register(example.definition(), example.executor())
        );
    }
}
