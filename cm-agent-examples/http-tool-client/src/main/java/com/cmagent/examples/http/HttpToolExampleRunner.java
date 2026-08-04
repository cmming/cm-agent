package com.cmagent.examples.http;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 根据显式开关校验配置并执行 HTTP 工具示例。
 */
@Component
public class HttpToolExampleRunner implements ApplicationRunner {
    private final HttpToolExampleProperties properties;
    private final CmAgentToolClient client;

    /**
     * 校验并构造 {@code HttpToolExampleRunner} 实例。
     *
     * @param properties 示例客户端配置
     * @param client 动态 HTTP 工具示例客户端
     */
    public HttpToolExampleRunner(HttpToolExampleProperties properties, CmAgentToolClient client) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    @Override
    /**
     * 校验示例配置后创建并调试动态 HTTP 工具。
     *
     * @param args 应用启动命令行参数
     */
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            System.out.println("HTTP 工具示例未启用；设置 CM_AGENT_HTTP_EXAMPLE_ENABLED=true 后执行。");
            return;
        }
        validate();
        CmAgentToolClient.ExampleResult result = client.createAndDebug();
        System.out.printf("HTTP 工具创建完成：toolId=%s，debug=%s%n",
                result.toolId(), result.debugResponse());
    }

    /**
     * 校验示例客户端所需的地址、JWT 和工具配置。
     */
    private void validate() {
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("CM Agent 服务地址不能为空");
        }
        if (isBlank(properties.getJwt())) {
            throw new IllegalStateException("CM Agent JWT 不能为空");
        }
        if (isBlank(properties.getToolName())) {
            throw new IllegalStateException("HTTP 工具名称不能为空");
        }
        if (isBlank(properties.getTargetUrl())) {
            throw new IllegalStateException("HTTP 目标 URL 不能为空");
        }
        if (isBlank(properties.getMessage())) {
            throw new IllegalStateException("调试消息不能为空");
        }
        boolean hasHeaderName = !isBlank(properties.getSecretHeaderName());
        boolean hasSecretRef = !isBlank(properties.getSecretRef());
        if (hasHeaderName != hasSecretRef) {
            throw new IllegalStateException("Secret Header 名称和引用必须同时提供");
        }
    }

    /**
     * 判断文本是否为空或仅包含空白字符。
     *
     * @param value 待校验或规范化的值。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
