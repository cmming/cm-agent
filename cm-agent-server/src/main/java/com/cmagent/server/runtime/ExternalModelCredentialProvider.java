package com.cmagent.server.runtime;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.server.config.AgentScopeRuntimeProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 按租户和模型配置读取外部模型凭据，避免把 API Key 放入领域配置。 */
public final class ExternalModelCredentialProvider implements ModelCredentialProvider {

    private final Map<CredentialKey, ModelCredential> credentials;

    public ExternalModelCredentialProvider(AgentScopeRuntimeProperties properties) {
        Objects.requireNonNull(properties, "AgentScope 运行时配置不能为空");
        properties.validate(false);
        Map<CredentialKey, ModelCredential> configuredCredentials = new LinkedHashMap<>();
        for (AgentScopeRuntimeProperties.CredentialProperties credential : properties.getCredentials()) {
            CredentialKey key = new CredentialKey(credential.getTenantId(), credential.getModelConfigId());
            if (configuredCredentials.containsKey(key)) {
                throw new IllegalStateException("模型凭据配置重复");
            }
            configuredCredentials.put(key, new ModelCredential(credential.getApiKey()));
        }
        this.credentials = Map.copyOf(configuredCredentials);
    }

    @Override
    /**
     * 按租户和模型配置解析外部凭据。
     *
     * @param tenantId 当前租户标识
     * @param modelConfigId 模型配置标识
     * @return 外部模型凭据
     * @throws IllegalStateException 未配置匹配凭据时抛出
     */
    public ModelCredential resolve(UUID tenantId, UUID modelConfigId) {
        ModelCredential credential = credentials.get(new CredentialKey(tenantId, modelConfigId));
        if (credential == null) {
            throw new ModelCredentialUnavailableException();
        }
        return credential;
    }

    @Override
    public String toString() {
        return "ExternalModelCredentialProvider[credentialCount=" + credentials.size() + "]";
    }

    private record CredentialKey(UUID tenantId, UUID modelConfigId) {
    }
}
