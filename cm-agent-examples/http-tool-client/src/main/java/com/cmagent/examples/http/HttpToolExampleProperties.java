package com.cmagent.examples.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 工具示例的外部运行参数。
 */
@ConfigurationProperties(prefix = "example.http-tool")
public class HttpToolExampleProperties {
    private boolean enabled;
    private String baseUrl = "http://localhost:8080";
    private String jwt = "";
    private String toolName = "developer-http-example";
    private String targetUrl = "";
    private String secretHeaderName = "";
    private String secretRef = "";
    private String message = "你好，CM Agent";

    /**
     * 返回是否在启动后运行示例客户端。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否在启动后运行示例客户端。
     *
     * @param enabled 是否启用示例执行
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回 CM Agent Server 基础地址。
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置 CM Agent Server 基础地址。
     *
     * @param baseUrl CM Agent Server 基础地址
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 返回调用 Server 使用的 JWT。
     */
    public String getJwt() {
        return jwt;
    }

    /**
     * 设置调用 Server 使用的 JWT。
     *
     * @param jwt 调用 Server 使用的 JWT
     */
    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    /**
     * 返回待创建的示例工具名称。
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 设置待创建的示例工具名称。
     *
     * @param toolName 示例工具名称
     */
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    /**
     * 返回示例工具请求的目标 URL 模板。
     */
    public String getTargetUrl() {
        return targetUrl;
    }

    /**
     * 设置示例工具请求的目标 URL 模板。
     *
     * @param targetUrl 目标 URL 模板
     */
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    /**
     * 返回目标服务要求的 Secret 请求头名称。
     */
    public String getSecretHeaderName() {
        return secretHeaderName;
    }

    /**
     * 设置目标服务要求的 Secret 请求头名称。
     *
     * @param secretHeaderName Secret 请求头名称
     */
    public void setSecretHeaderName(String secretHeaderName) {
        this.secretHeaderName = secretHeaderName;
    }

    /**
     * 返回 Server 端解析的 Secret 引用。
     */
    public String getSecretRef() {
        return secretRef;
    }

    /**
     * 设置 Server 端解析的 Secret 引用。
     *
     * @param secretRef Server 端 Secret 引用
     */
    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    /**
     * 返回示例调试消息。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置示例调试消息。
     *
     * @param message 示例调试消息
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
