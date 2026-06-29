package com.cmagent.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@ConfigurationProperties(prefix = "cm-agent.security")
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

    public boolean isBootstrapAdminEnabled() {
        return bootstrapAdminEnabled;
    }

    public void setBootstrapAdminEnabled(boolean bootstrapAdminEnabled) {
        this.bootstrapAdminEnabled = bootstrapAdminEnabled;
    }

    public String getBootstrapAdminUsername() {
        return blankToDefault(bootstrapAdminUsername, DEFAULT_USERNAME);
    }

    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public String getBootstrapAdminDisplayName() {
        return blankToDefault(bootstrapAdminDisplayName, DEFAULT_DISPLAY_NAME);
    }

    public void setBootstrapAdminDisplayName(String bootstrapAdminDisplayName) {
        this.bootstrapAdminDisplayName = bootstrapAdminDisplayName;
    }

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
