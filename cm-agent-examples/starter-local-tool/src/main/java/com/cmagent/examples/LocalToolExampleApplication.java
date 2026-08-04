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
    /**
     * 启动示例 Spring Boot 应用。
     *
     * @param args 应用启动命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LocalToolExampleApplication.class, args);
    }

    /**
     * 应用启动后分别调用两个已注册工具，展示最小执行流程。
     *
     * @param registry 本地工具执行器注册表
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

    /**
     * 按工具名称调用本地执行器并打印结果。
     *
     * @param registry 本地工具执行器注册表
     * @param name 待调用工具名称
     * @param toolId 目标工具标识。
     * @param inputJson 序列化后的工具输入 JSON。
     */
    private static void executeAndPrint(ToolRegistry registry, String name, UUID toolId, String inputJson) {
        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(toolId, inputJson));
        System.out.printf("LOCAL 工具 %s：success=%s，result=%s%n",
                name, result.success(), result.success() ? result.outputSummary() : result.errorMessage());
    }
}
