package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ConfigurationProperties(prefix = "cm-agent.agentscope")
/** AgentScope 运行时开关及外部模型凭据配置属性。 */
public class AgentScopeRuntimeProperties {

    private boolean enabled;
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration toolTimeout = Duration.ofSeconds(30);
    private int modelMaxAttempts = 2;
    private List<CredentialProperties> credentials = List.of();

    /**
     * @return 是否启用 AgentScope 真实运行时。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用 AgentScope 真实运行时。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 模型调用超时时间。
     */
    public Duration getModelTimeout() {
        return modelTimeout;
    }

    /**
     * @param modelTimeout 模型调用超时时间，必须为正数。
     */
    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }

    /**
     * @return 工具调用超时时间。
     */
    public Duration getToolTimeout() {
        return toolTimeout;
    }

    /**
     * @param toolTimeout 工具调用超时时间，必须为正数。
     */
    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    /**
     * @return 模型调用最大尝试次数。
     */
    public int getModelMaxAttempts() {
        return modelMaxAttempts;
    }

    /**
     * @param modelMaxAttempts 模型调用最大尝试次数，范围为 1 到 5。
     */
    public void setModelMaxAttempts(int modelMaxAttempts) {
        this.modelMaxAttempts = modelMaxAttempts;
    }

    /**
     * @return 外部模型凭据配置列表。
     */
    public List<CredentialProperties> getCredentials() {
        return credentials;
    }

    /**
     * @param credentials 外部模型凭据配置列表，将被复制为不可变列表。
     */
    public void setCredentials(List<CredentialProperties> credentials) {
        this.credentials = List.copyOf(credentials);
    }

    /**
     * 校验运行时开关、超时、重试次数以及外部凭据。
     *
     * @param fakeRuntimeEnabled 是否同时启用了 fake runtime
     * @throws IllegalStateException 配置不合法或真实运行时与 fake runtime 同时启用时抛出
     */
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

        /**
         * @return 凭据所属租户标识。
         */
        public UUID getTenantId() {
            return tenantId;
        }

        /**
         * @param tenantId 凭据所属租户标识。
         */
        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * @return 模型配置标识。
         */
        public UUID getModelConfigId() {
            return modelConfigId;
        }

        /**
         * @param modelConfigId 模型配置标识。
         */
        public void setModelConfigId(UUID modelConfigId) {
            this.modelConfigId = modelConfigId;
        }

        /**
         * @return 模型 API Key；调用方不得记录或返回该值。
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * @param apiKey 模型 API Key，仅用于运行时认证，不得写入日志。
         */
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
