package com.cmagent.server.runtime;

import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseModelCredentialProviderTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void 按租户和模型配置读取数据库密文() {
        ModelCredentialCipher cipher = cipher();
        DatabaseModelCredentialProvider provider = new DatabaseModelCredentialProvider(
                repository(TENANT_A, MODEL_ID, cipher.encrypt("unit-test-model-key")), cipher);

        assertThat(provider.resolve(TENANT_A, MODEL_ID).apiKey()).isEqualTo("unit-test-model-key");
        assertThat(provider.toString()).doesNotContain("unit-test-model-key");
    }

    @Test
    void 跨租户或无效密文不会泄露凭据() {
        ModelCredentialCipher cipher = cipher();
        DatabaseModelCredentialProvider provider = new DatabaseModelCredentialProvider(
                repository(TENANT_A, MODEL_ID, "invalid-ciphertext"), cipher);

        assertThatThrownBy(() -> provider.resolve(TENANT_B, MODEL_ID))
                .isInstanceOf(ModelCredentialUnavailableException.class)
                .hasMessage("模型凭据不可用");
        assertThatThrownBy(() -> provider.resolve(TENANT_A, MODEL_ID))
                .isInstanceOf(ModelCredentialUnavailableException.class)
                .hasMessage("模型凭据不可用")
                .hasMessageNotContaining("invalid-ciphertext");
    }

    private static ModelCredentialCipher cipher() {
        byte[] key = Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        return new ModelCredentialCipher(new SecretKeySpec(key, "AES"));
    }

    private static ModelConfigRepository repository(UUID tenantId, UUID modelConfigId, String encryptedApiKey) {
        return new ModelConfigRepository() {
            @Override
            public ModelConfig save(ModelConfig modelConfig) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelConfig update(ModelConfig modelConfig) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ModelConfig> findByTenantAndId(UUID requestedTenantId, UUID requestedModelConfigId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findEncryptedApiKeyByTenantAndId(UUID requestedTenantId, UUID requestedModelConfigId) {
                return tenantId.equals(requestedTenantId) && modelConfigId.equals(requestedModelConfigId)
                        ? Optional.of(encryptedApiKey)
                        : Optional.empty();
            }

            @Override
            public List<ModelConfig> listByTenant(UUID requestedTenantId) {
                return List.of();
            }

            @Override
            public boolean isReferencedByAgent(UUID requestedTenantId, UUID requestedModelConfigId) {
                return false;
            }

            @Override
            public boolean delete(UUID requestedTenantId, UUID requestedModelConfigId) {
                return false;
            }
        };
    }
}
