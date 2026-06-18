package com.cmagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.cmagent",
        exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class}
)
public class CmAgentServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CmAgentServerApplication.class, args);
    }
}
