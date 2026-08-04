package com.cmagent.examples.local;

import com.cmagent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在应用初始化阶段注册 LOCAL 示例工具。
 */
@Configuration(proxyBeanMethods = false)
public class LocalToolRegistration {

    @Bean
    /**
     * 创建本地示例执行器共享的 JSON 映射器。
     */
    ObjectMapper localToolObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    /**
     * 将示例执行器注册到进程内工具注册表。
     *
     * @param registry 本地工具执行器注册表
     * @param objectMapper JSON 映射器
     */
    InitializingBean registerLocalTools(ToolRegistry registry, ObjectMapper objectMapper) {
        return () -> {
            registry.register(LocalToolDefinitions.echo(), new EchoToolExecutor(objectMapper));
            registry.register(LocalToolDefinitions.add(), new AddToolExecutor(objectMapper));
        };
    }
}
