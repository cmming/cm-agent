package com.cmagent.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cm-agent")
/** Starter 可绑定的基础配置项。 */
public class CmAgentProperties {

    private boolean fakeRuntimeEnabled = true;

    private String defaultTenantCode = "default";

    /**
     * 返回是否启用本地模拟运行时。
     *
     * @return {@code true} 表示使用 fake runtime
     */
    public boolean isFakeRuntimeEnabled() {
        return fakeRuntimeEnabled;
    }

    /**
     * 设置是否启用本地模拟运行时。
     *
     * @param fakeRuntimeEnabled 是否启用 fake runtime
     */
    public void setFakeRuntimeEnabled(boolean fakeRuntimeEnabled) {
        this.fakeRuntimeEnabled = fakeRuntimeEnabled;
    }

    /**
     * 返回未显式指定租户时使用的默认租户编码。
     *
     * @return 默认租户编码
     */
    public String getDefaultTenantCode() {
        return defaultTenantCode;
    }

    /**
     * 设置未显式指定租户时使用的默认租户编码。
     *
     * @param defaultTenantCode 默认租户编码
     */
    public void setDefaultTenantCode(String defaultTenantCode) {
        this.defaultTenantCode = defaultTenantCode;
    }
}
