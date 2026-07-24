package com.cmagent.server.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Configuration
/** 将 JWT 服务接入 Spring Security 的配置适配层。 */
public class JwtSecurityConfiguration {
    /**
     * 从外部配置创建 JWT 签名密钥。
     *
     * @param configuredSecret JWT 签名密钥原文
     * @return HMAC JWT 签名密钥
     * @throws IllegalStateException 未配置签名密钥时抛出
     * @throws RuntimeException      密钥长度不满足算法要求时抛出
     */
    @Bean
    SecretKey jwtSigningKey(@Value("${cm-agent.security.jwt-secret:}") String configuredSecret) {
        String secret = configuredSecret == null ? "" : configuredSecret.trim();
        if (secret.isEmpty()) {
            throw new IllegalStateException("未配置 cm-agent.security.jwt-secret");
        }
        return createKey(secret);
    }

    private SecretKey createKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
