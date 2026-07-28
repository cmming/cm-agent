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

    public HttpToolExampleRunner(HttpToolExampleProperties properties, CmAgentToolClient client) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    @Override
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
