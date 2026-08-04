package com.cmagent.examples.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * HTTP 工具客户端示例入口。
 */
@SpringBootApplication
@EnableConfigurationProperties(HttpToolExampleProperties.class)
public class HttpToolExampleApplication {
    /**
     * 启动示例 Spring Boot 应用。
     *
     * @param args 应用启动命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HttpToolExampleApplication.class, args);
    }
}
