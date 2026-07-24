package com.cmagent.server.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
/** 将工具输出限制为安全摘要，避免把内部错误或敏感字段直接返回给调用方。 */
public class ToolOutputSanitizer {
    private static final String MASK = "<已脱敏>";
    private static final String URL_MASK = "<已脱敏URL>";
    private static final String STACK_MASK = "<已脱敏异常>";
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern AUTH_CREDENTIAL = Pattern.compile(
            "(?i)(?:Basic|Bearer|Digest|Negotiate)\\s+[^\\s\\\"',;}]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:authorization|set[-_.\\s]?cookie|cookie|access[-_.\\s]?token|" +
                    "refresh[-_.\\s]?token|api[-_.\\s]?key|client[-_.\\s]?secret|jwt[-_.\\s]?secret|" +
                    "password|passwd|secret|token)[\\\"']?\\s*[:=]\\s*)" +
                    "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}\\]]+)"
    );
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?im)^(?:authorization|cookie|set-cookie)\\s*[:=][^\\r\\n]*$"
    );
    private static final Pattern STACK_DETAIL = Pattern.compile(
            "(?im)(?:^|\\n)\\s*(?:at\\s+|caused by:|suppressed:|[a-z_$][\\w$]*(?:\\.[\\w$]+)+(?:exception|error)\\s*:)|" +
                    "\\bat\\s+[a-z_$][\\w$]*(?:\\.[\\w$]+)+\\([^\\r\\n)]*(?:\\.java:\\d+|unknown source|native method)\\)"
    );
    private static final Set<String> SENSITIVE_JSON_KEYS = Set.of(
            "authorization", "cookie", "setcookie", "token", "accesstoken", "refreshtoken",
            "authtoken", "apikey", "secret", "clientsecret", "password", "passwd", "jwtsecret"
    );
    private static final Set<String> SENSITIVE_JSON_SUFFIXES = Set.of(
            "authorization", "authtoken", "accesstoken", "refreshtoken", "apikey", "secret", "clientsecret",
            "jwtsecret", "password", "passwd", "token"
    );

    private final ObjectMapper objectMapper;

    public ToolOutputSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将工具输出转换为受控摘要，并移除已知 secret 值。
     *
     * @param value 工具原始输出
     * @param secretValues 需要从输出中替换的敏感值
     * @return 脱敏且受长度限制的输出摘要
     * @throws IllegalArgumentException 输出无法按 JSON 处理且不满足文本要求时抛出
     */
    public String sanitize(String value, List<String> secretValues) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            JsonNode json = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (json != null && !json.isMissingNode()) {
                return objectMapper.writeValueAsString(sanitizeJson(json, secretValues));
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 输出按文本规则脱敏。
        }
        return sanitizeText(value, secretValues);
    }

    /**
     * 判断字符串按 UTF-8 编码后的字节数是否超过限制。
     *
     * @param value 待检查字符串
     * @param maxBytes 最大允许字节数
     * @return 超过限制返回 {@code true}，否则返回 {@code false}
     */
    public boolean exceedsByteLimit(String value, int maxBytes) {
        return value.getBytes(StandardCharsets.UTF_8).length > maxBytes;
    }

    private JsonNode sanitizeJson(JsonNode node, List<String> secretValues) {
        if (node.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (isSensitiveJsonKey(normalizeSensitiveKey(fieldName))) {
                    ((ObjectNode) node).set(fieldName, TextNode.valueOf(MASK));
                } else {
                    ((ObjectNode) node).set(fieldName, sanitizeJson(node.get(fieldName), secretValues));
                }
            }
            return node;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                ((ArrayNode) node).set(index, sanitizeJson(node.get(index), secretValues));
            }
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.textValue(), secretValues));
        }
        return node;
    }

    private String sanitizeText(String value, List<String> secretValues) {
        String redacted = value;
        for (String secret : secretValues) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, MASK);
            }
        }
        redacted = SENSITIVE_HEADER.matcher(redacted).replaceAll(MASK);
        redacted = URL.matcher(redacted).replaceAll(URL_MASK);
        redacted = AUTH_CREDENTIAL.matcher(redacted).replaceAll(MASK);
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1" + MASK);
        return STACK_DETAIL.matcher(redacted).find() ? STACK_MASK : redacted;
    }

    private String normalizeSensitiveKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean isSensitiveJsonKey(String normalizedKey) {
        if (SENSITIVE_JSON_KEYS.contains(normalizedKey)) {
            return true;
        }
        return SENSITIVE_JSON_SUFFIXES.stream()
                .anyMatch(suffix -> normalizedKey.length() > suffix.length() && normalizedKey.endsWith(suffix));
    }
}
