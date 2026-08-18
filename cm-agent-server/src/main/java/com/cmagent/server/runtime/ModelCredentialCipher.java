package com.cmagent.server.runtime;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** 使用随机 IV 的 AES/GCM 对数据库中的模型 API Key 进行认证加密。 */
public final class ModelCredentialCipher {
    private static final String VERSION = "v1";
    private static final int IV_LENGTH = 12;
    private static final int AUTH_TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param secretKey 仅由受控配置提供的 AES-256 主密钥
     */
    public ModelCredentialCipher(SecretKey secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "模型凭据加密主密钥不能为空");
    }

    /**
     * 加密待存储的模型 API Key。
     *
     * @param apiKey 模型供应商 API Key
     * @return 带版本和随机 IV 的 Base64 密文
     */
    public String encrypt(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 不能为空");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return VERSION + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("模型凭据加密失败", exception);
        }
    }

    /**
     * 解密运行时使用的模型 API Key；任何格式、完整性或密钥错误均不向调用方泄露细节。
     *
     * @param encryptedApiKey 数据库中的密文
     * @return 仅供当前模型调用使用的明文 API Key
     */
    public String decrypt(String encryptedApiKey) {
        try {
            String[] parts = encryptedApiKey == null ? new String[0] : encryptedApiKey.split(":", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unsupported payload");
            }
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[1]);
            if (iv.length != IV_LENGTH) {
                throw new IllegalArgumentException("invalid iv");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(decoder.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("模型凭据密文不可用", exception);
        }
    }
}
