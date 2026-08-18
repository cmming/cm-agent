package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/** 模型凭据加密主密钥配置；它不是任何模型供应商的 API Key。 */
@ConfigurationProperties(prefix = "cm-agent.model-credentials")
public class ModelCredentialProperties {

    private String encryptionKey;

    /**
     * @return Base64 编码的 256 位 AES 主密钥
     */
    public String getEncryptionKey() {
        return encryptionKey;
    }

    /**
     * @param encryptionKey Base64 编码的 256 位 AES 主密钥
     */
    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /**
     * 解析并校验 AES 主密钥。
     *
     * <p>主密钥只能通过受控环境变量或密钥管理系统注入；数据库中的模型 API Key
     * 使用该密钥加密后才会落库。</p>
     *
     * @return 可供 AES/GCM 使用的密钥
     */
    public SecretKey resolveSecretKey() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("模型凭据加密主密钥不能为空");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey);
            if (decoded.length != 32) {
                throw new IllegalStateException("模型凭据加密主密钥必须是 Base64 编码的 256 位 AES 密钥");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("模型凭据加密主密钥必须是有效的 Base64 字符串", exception);
        }
    }

    @Override
    public String toString() {
        return "ModelCredentialProperties[encryptionKey=<已脱敏>]";
    }
}
