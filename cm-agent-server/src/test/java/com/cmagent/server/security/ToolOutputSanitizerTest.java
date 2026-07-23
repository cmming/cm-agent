package com.cmagent.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputSanitizerTest {

    private final ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(new ObjectMapper());

    @Test
    void recursivelyRedactsJsonSecretsUrlsAndExceptionDetails() {
        String output = sanitizer.sanitize("""
                {"access_token":"token-value","cookie":"cookie-value","safe":"https://private.example.test/a",
                "nested":{"client_secret":"secret-value","trace":"java.lang.IllegalStateException: hidden"}}
                """, java.util.List.of("known-secret"));

        assertThat(output).doesNotContain("token-value", "cookie-value", "secret-value", "https://", "IllegalStateException");
    }

    @Test
    void redactsQuotedTextKeysAuthorizationUrlsAndSuppressedStackDetails() {
        String output = sanitizer.sanitize("""
                payload={"access_token":"token-value","client_secret":"secret-value"}
                Authorization: Bearer auth-value
                endpoint=https://private.example.test/a
                Caused by: java.lang.IllegalStateException: hidden
                """, java.util.List.of());

        assertThat(output).doesNotContain("token-value", "secret-value", "auth-value", "https://", "Caused by", "IllegalStateException");
    }

    @Test
    void 动态HTTP工具运维文档声明安全边界和MCP调用条件() throws IOException {
        String readme = readRepositoryFile("README.md");
        String configuration = readRepositoryFile("docs/configuration.md");
        String operations = readRepositoryFile("docs/operations.md");

        assertThat(readme).contains("动态 HTTP 工具", "控制台", "MCP");
        assertThat(configuration).contains(
                "JSON Pointer", "secret/...", "allowed-hosts", "tool:mcp:invoke", "tool:debug",
                "响应上限", "重定向"
        );
        assertThat(operations).contains("幂等", "MCP", "HTTP 工具", "SecretProvider");
    }

    private String readRepositoryFile(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IOException("未找到仓库文件：" + relativePath);
    }
}
