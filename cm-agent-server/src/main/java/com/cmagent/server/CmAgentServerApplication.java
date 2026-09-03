package com.cmagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.cmagent.server.config.AgentScopeRuntimeProperties;

/** CM Agent 服务端应用入口，负责装配 Starter、持久化和服务端 Web 能力。 */
@SpringBootApplication(
        scanBasePackages = "com.cmagent",
        exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class}
)
@EnableConfigurationProperties(AgentScopeRuntimeProperties.class)
public class CmAgentServerApplication {
    /**
     * 启动 Spring Boot 服务端应用。
     *
     * @param args 应用启动命令行参数。
     */
    public static void main(String[] args) {
        SpringApplication.run(CmAgentServerApplication.class, args);
    }
}
