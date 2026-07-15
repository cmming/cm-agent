package com.cmagent.server.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SensitiveDataRedactor {
    private static final String MASK = "<已脱敏>";
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:(?:postgresql|mysql)://[^\\s\\\"']+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|api[-_]?key|jwt[-_]?secret|secret|token)\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)"
    );

    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JDBC_URL.matcher(value).replaceAll(MASK);
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer " + MASK);
        return SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=" + MASK);
    }
}
