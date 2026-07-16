package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ConfigurationProperties(prefix = "cm-agent.agentscope")
public class AgentScopeRuntimeProperties {

    private boolean enabled;
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration toolTimeout = Duration.ofSeconds(30);
    private int modelMaxAttempts = 2;
    private List<CredentialProperties> credentials = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getModelTimeout() {
        return modelTimeout;
    }

    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public int getModelMaxAttempts() {
        return modelMaxAttempts;
    }

    public void setModelMaxAttempts(int modelMaxAttempts) {
        this.modelMaxAttempts = modelMaxAttempts;
    }

    public List<CredentialProperties> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<CredentialProperties> credentials) {
        this.credentials = List.copyOf(credentials);
    }

    public void validate(boolean fakeRuntimeEnabled) {
        if (!isPositive(modelTimeout)) {
            throw new IllegalStateException("模型超时时间必须为正数");
        }
        if (!isPositive(toolTimeout)) {
            throw new IllegalStateException("工具超时时间必须为正数");
        }
        if (modelMaxAttempts < 1 || modelMaxAttempts > 5) {
            throw new IllegalStateException("模型最大尝试次数必须在 1 到 5 之间");
        }
        if (enabled && fakeRuntimeEnabled) {
            throw new IllegalStateException("AgentScope 真实运行时与 fake runtime 不能同时启用");
        }
        for (CredentialProperties credential : credentials) {
            credential.validate();
        }
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    @Override
    public String toString() {
        return "AgentScopeRuntimeProperties[enabled=" + enabled
                + ", modelTimeout=" + modelTimeout
                + ", toolTimeout=" + toolTimeout
                + ", modelMaxAttempts=" + modelMaxAttempts
                + ", credentialCount=" + credentials.size() + "]";
    }

    public static class CredentialProperties {

        private UUID tenantId;
        private UUID modelConfigId;
        private String apiKey;

        public UUID getTenantId() {
            return tenantId;
        }

        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }

        public UUID getModelConfigId() {
            return modelConfigId;
        }

        public void setModelConfigId(UUID modelConfigId) {
            this.modelConfigId = modelConfigId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        private void validate() {
            if (tenantId == null) {
                throw new IllegalStateException("模型凭据 tenantId 不能为空");
            }
            if (modelConfigId == null) {
                throw new IllegalStateException("模型凭据 modelConfigId 不能为空");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("模型凭据 API Key 不能为空");
            }
        }

        @Override
        public String toString() {
            return "CredentialProperties[tenantId=" + tenantId
                    + ", modelConfigId=" + modelConfigId
                    + ", apiKey=<已脱敏>]";
        }
    }
}
