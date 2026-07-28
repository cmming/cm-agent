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
    ObjectMapper localToolObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    InitializingBean registerLocalTools(ToolRegistry registry, ObjectMapper objectMapper) {
        return () -> {
            registry.register(LocalToolDefinitions.echo(), new EchoToolExecutor(objectMapper));
            registry.register(LocalToolDefinitions.add(), new AddToolExecutor(objectMapper));
        };
    }
}
