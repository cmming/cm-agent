package com.cmagent.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cm-agent")
public class CmAgentProperties {

    private boolean fakeRuntimeEnabled = true;

    private String defaultTenantCode = "default";

    private final Agentscope agentscope = new Agentscope();

    public Agentscope getAgentscope() {
        return agentscope;
    }

    public static class Agentscope {
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private Duration timeout = Duration.ofSeconds(60);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public boolean isFakeRuntimeEnabled() {
        return fakeRuntimeEnabled;
    }

    public void setFakeRuntimeEnabled(boolean fakeRuntimeEnabled) {
        this.fakeRuntimeEnabled = fakeRuntimeEnabled;
    }

    public String getDefaultTenantCode() {
        return defaultTenantCode;
    }

    public void setDefaultTenantCode(String defaultTenantCode) {
        this.defaultTenantCode = defaultTenantCode;
    }
}
