package com.cmagent.server.runtime;

import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;

import java.util.Objects;
import java.util.UUID;

/** 从租户隔离的模型配置仓储读取密文，并仅在模型调用期间解密 API Key。 */
public final class DatabaseModelCredentialProvider implements ModelCredentialProvider {
    private final ModelConfigRepository repository;
    private final ModelCredentialCipher cipher;

    /**
     * @param repository 模型配置仓储
     * @param cipher      模型凭据编解码器
     */
    public DatabaseModelCredentialProvider(ModelConfigRepository repository, ModelCredentialCipher cipher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.cipher = Objects.requireNonNull(cipher, "cipher 不能为空");
    }

    @Override
    public ModelCredential resolve(UUID tenantId, UUID modelConfigId) {
        try {
            String encryptedApiKey = repository.findEncryptedApiKeyByTenantAndId(tenantId, modelConfigId)
                    .orElseThrow(ModelCredentialUnavailableException::new);
            return new ModelCredential(cipher.decrypt(encryptedApiKey));
        } catch (ModelCredentialUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelCredentialUnavailableException(exception);
        }
    }

    @Override
    public String toString() {
        return "DatabaseModelCredentialProvider";
    }
}
