package com.cmagent.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
/** 负责签发和解析 JWT；密钥只从外部配置读取，不提供生产默认值。 */
public class JwtService {
    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    private final SecretKey signingKey;

    public JwtService(SecretKey jwtSigningKey) {
        this.signingKey = jwtSigningKey;
    }

    /**
     * 为指定租户主体签发访问令牌。
     *
     * @param tenantId    主体所属租户
     * @param principalId 主体标识
     * @param displayName 主体展示名称
     * @param permissions 令牌中携带的权限集合
     * @return 签名后的 JWT 字符串
     * @throws IllegalArgumentException 输入主体信息不完整时抛出
     */
    public String createToken(UUID tenantId, String principalId, String displayName, List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principalId)
                .claim("tenantId", tenantId.toString())
                .claim("displayName", displayName)
                .claim("permissions", List.copyOf(permissions))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 验证令牌签名和有效期，并解析出受信任的会话主体。
     *
     * @param token 待验证的 JWT 原文
     * @return 已验证的租户、主体和权限信息
     * @throws JwtException 令牌格式错误、签名不匹配或已经失效时抛出
     */
    public JwtSession parseAndVerify(String token) throws JwtException {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
        Claims claims = jws.getPayload();
        List<?> permissionValues = claims.get("permissions", List.class);
        List<String> permissions = permissionValues == null ? List.of() : permissionValues.stream().map(Object::toString).toList();
        return new JwtSession(
                UUID.fromString(claims.get("tenantId", String.class)),
                claims.getSubject(),
                claims.get("displayName", String.class),
                permissions
        );
    }

    public record JwtSession(UUID tenantId, String principalId, String displayName, List<String> permissions) {
    }
}
