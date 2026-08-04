package com.cmagent.server.runtime;

import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.server.config.AgentScopeRuntimeProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 按租户和模型配置读取外部模型凭据，避免把 API Key 放入领域配置。
 */
public final class ExternalModelCredentialProvider implements ModelCredentialProvider {

    private final Map<CredentialKey, ModelCredential> credentials;
    /**
     * 创建 {@code ExternalModelCredentialProvider} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     */
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
    /**
     * 返回不暴露凭据等敏感字段的安全文本摘要。
     */
    public String toString() {
        return "ExternalModelCredentialProvider[credentialCount=" + credentials.size() + "]";
    }

    /**
     * 封装 {@code CredentialKey} 在当前流程中使用的不可变数据。
     */
    private record CredentialKey(UUID tenantId, UUID modelConfigId) {
    }
}
