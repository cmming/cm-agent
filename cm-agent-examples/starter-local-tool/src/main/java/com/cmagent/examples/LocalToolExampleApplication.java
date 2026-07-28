package com.cmagent.examples;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.examples.local.LocalToolDefinitions;
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

    /**
     * 应用启动后分别调用两个已注册工具，展示最小执行流程。
     */
    @Bean
    CommandLineRunner runLocalToolExamples(ToolRegistry registry) {
        return args -> {
            executeAndPrint(
                    registry,
                    "echo",
                    LocalToolDefinitions.ECHO_TOOL_ID,
                    "{\"message\":\"你好，CM Agent\"}"
            );
            executeAndPrint(
                    registry,
                    "add",
                    LocalToolDefinitions.ADD_TOOL_ID,
                    "{\"left\":0.1,\"right\":0.2}"
            );
        };
    }

    private static void executeAndPrint(ToolRegistry registry, String name, UUID toolId, String inputJson) {
        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, inputJson));
        System.out.printf("LOCAL 工具 %s：success=%s，result=%s%n",
                name, result.success(), result.success() ? result.outputSummary() : result.errorMessage());
    }
}
