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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getJwt() {
        return jwt;
    }

    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getSecretHeaderName() {
        return secretHeaderName;
    }

    public void setSecretHeaderName(String secretHeaderName) {
        this.secretHeaderName = secretHeaderName;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
