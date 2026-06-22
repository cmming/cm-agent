package com.cmagent.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cm-agent")
public class CmAgentProperties {

    private boolean fakeRuntimeEnabled = true;

    private String defaultTenantCode = "default";

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
