package com.cmagent.examples;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

@SpringBootApplication
public class LocalToolExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocalToolExampleApplication.class, args);
    }

    @Bean
    CommandLineRunner registerEchoTool(ToolRegistry registry) {
        return args -> registry.register(
                new ToolDefinition(
                        UUID.fromString("00000000-0000-0000-0000-000000000101"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "echo",
                        "回显输入",
                        ToolType.LOCAL,
                        "{\"type\":\"object\"}",
                        ToolRiskLevel.LOW,
                        true,
                        "",
                        "example",
                        "example"
                ),
                request -> new ToolExecutionResult("示例工具收到：" + request.inputJson(), true)
        );
    }
}
