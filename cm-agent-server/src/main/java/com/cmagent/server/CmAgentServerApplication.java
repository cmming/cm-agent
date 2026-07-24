package com.cmagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.cmagent",
        exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class}
)
/** CM Agent 服务端应用入口，负责装配 Starter、持久化和服务端 Web 能力。 */
public class CmAgentServerApplication {
    /**
     * main：处理该类内部的业务逻辑或辅助计算。
     */
    public static void main(String[] args) {
        SpringApplication.run(CmAgentServerApplication.class, args);
    }
}
