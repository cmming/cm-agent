package com.cmagent.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            "cm-agent-server-local-jwt-signing-key-2026-06-19-strong".getBytes(StandardCharsets.UTF_8)
    );
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    public String createToken(UUID tenantId, String principalId, String displayName, List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principalId)
                .claim("tenantId", tenantId.toString())
                .claim("displayName", displayName)
                .claim("permissions", List.copyOf(permissions))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(SIGNING_KEY)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public JwtSession parseAndVerify(String token) throws JwtException {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(SIGNING_KEY)
                .build()
                .parseSignedClaims(token);
        Claims claims = jws.getPayload();
        List<String> permissions = ((List<Object>) claims.get("permissions", List.class))
                .stream()
                .map(Object::toString)
                .toList();
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
