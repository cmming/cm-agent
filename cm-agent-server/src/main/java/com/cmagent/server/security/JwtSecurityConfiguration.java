package com.cmagent.server.security;

import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Configuration
public class JwtSecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(JwtSecurityConfiguration.class);
    private static final String LOCAL_FALLBACK_SECRET = "cm-agent-local-jwt-secret-only-for-dev-and-tests-2026";

    @Bean
    SecretKey jwtSigningKey(
            @Value("${cm-agent.security.jwt-secret:}") String configuredSecret,
            @Value("${cm-agent.security.allow-dev-jwt-fallback:false}") boolean allowDevJwtFallback,
            Environment environment
    ) {
        String secret = configuredSecret == null ? "" : configuredSecret.trim();
        String[] activeProfiles = environment.getActiveProfiles();
        if (hasProductionLikeProfile(activeProfiles) && hasTestProfile(activeProfiles)) {
            throw new IllegalStateException("production/prod profile 禁止与 test profile 同时启用，测试 JWT 配置不得用于生产样环境");
        }

        if (!secret.isEmpty()) {
            return createKey(secret);
        }

        if (allowFallback(activeProfiles, allowDevJwtFallback)) {
            log.warn("未配置 cm-agent.security.jwt-secret，当前仅使用本地/测试回退密钥，生产环境必须外部提供。");
            return createKey(LOCAL_FALLBACK_SECRET);
        }

        throw new IllegalStateException("未配置 cm-agent.security.jwt-secret；生产环境必须外部提供 JWT 密钥");
    }

    private boolean allowFallback(String[] activeProfiles, boolean allowDevJwtFallback) {
        if (hasProductionLikeProfile(activeProfiles)) {
            return false;
        }
        if (!allowDevJwtFallback) {
            return false;
        }
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("local") || profile.equals("test"));
    }

    private boolean hasProductionLikeProfile(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("production") || profile.equals("prod"));
    }

    private boolean hasTestProfile(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("test"));
    }

    private SecretKey createKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
