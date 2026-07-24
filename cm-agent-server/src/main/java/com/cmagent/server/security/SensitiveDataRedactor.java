package com.cmagent.server.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
/** 对日志、错误和审计文本中的密钥、令牌等敏感内容进行脱敏。 */
public class SensitiveDataRedactor {
    private static final String MASK = "<已脱敏>";
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:(?:postgresql|mysql)://[^\\s\\\"']+");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^\\s\\\"']+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|api[-_]?key|jwt[-_]?secret|secret|token)\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)"
    );

    /**
     * 脱敏字符串中的令牌、密钥和密码等敏感片段。
     *
     * @param value 待脱敏文本，可以为空
     * @return 脱敏后的文本；输入为空时返回空值
     */
    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JDBC_URL.matcher(value).replaceAll(MASK);
        redacted = HTTP_URL.matcher(redacted).replaceAll(MASK);
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer " + MASK);
        return SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=" + MASK);
    }
}
