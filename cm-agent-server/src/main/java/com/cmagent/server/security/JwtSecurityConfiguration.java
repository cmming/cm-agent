package com.cmagent.server.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtSecurityConfiguration {
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
