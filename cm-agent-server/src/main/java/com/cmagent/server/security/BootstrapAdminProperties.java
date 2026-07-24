package com.cmagent.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@ConfigurationProperties(prefix = "cm-agent.security")
/** 本地/测试 bootstrap admin 配置；生产 profile 必须显式禁止该能力。 */
public class BootstrapAdminProperties {
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_DISPLAY_NAME = "系统管理员";

    private final Environment environment;
    private boolean bootstrapAdminEnabled;
    private String bootstrapAdminUsername = DEFAULT_USERNAME;
    private String bootstrapAdminPassword = "";
    private String bootstrapAdminDisplayName = DEFAULT_DISPLAY_NAME;

    public BootstrapAdminProperties(Environment environment) {
        this.environment = environment;
    }

    /** @return 是否启用 bootstrap admin。 */
    public boolean isBootstrapAdminEnabled() {
        return bootstrapAdminEnabled;
    }

    /** @param bootstrapAdminEnabled 是否启用 bootstrap admin。 */
    public void setBootstrapAdminEnabled(boolean bootstrapAdminEnabled) {
        this.bootstrapAdminEnabled = bootstrapAdminEnabled;
    }

    /** @return bootstrap admin 用户名；为空时使用本地默认用户名。 */
    public String getBootstrapAdminUsername() {
        return blankToDefault(bootstrapAdminUsername, DEFAULT_USERNAME);
    }

    /** @param bootstrapAdminUsername bootstrap admin 用户名。 */
    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    /** @return bootstrap admin 密码；调用方不得记录该值。 */
    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
    }

    /** @param bootstrapAdminPassword bootstrap admin 密码，仅用于本地/测试认证。 */
    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    /** @return bootstrap admin 展示名称。 */
    public String getBootstrapAdminDisplayName() {
        return blankToDefault(bootstrapAdminDisplayName, DEFAULT_DISPLAY_NAME);
    }

    /** @param bootstrapAdminDisplayName bootstrap admin 展示名称。 */
    public void setBootstrapAdminDisplayName(String bootstrapAdminDisplayName) {
        this.bootstrapAdminDisplayName = bootstrapAdminDisplayName;
    }

    /**
     * 校验 bootstrap admin 是否违反生产 profile 安全约束。
     *
     * @throws IllegalStateException 生产环境启用 bootstrap admin 或缺少密码时抛出
     */
    public void validate() {
        if (!bootstrapAdminEnabled) {
            return;
        }
        if (hasProductionProfile()) {
            String profileLabel = hasSupabaseProfile()
                    ? "production/prod/supabase profile"
                    : "production/prod profile";
            throw new IllegalStateException(profileLabel + " 禁止启用 bootstrap admin");
        }
        if (getBootstrapAdminPassword().isBlank()) {
            throw new IllegalStateException("启用 bootstrap admin 时必须配置 cm-agent.security.bootstrap-admin-password");
        }
    }

    private boolean hasProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equalsIgnoreCase(profile)
                        || "prod".equalsIgnoreCase(profile)
                        || "supabase".equalsIgnoreCase(profile));
    }

    private boolean hasSupabaseProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "supabase".equalsIgnoreCase(profile));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
