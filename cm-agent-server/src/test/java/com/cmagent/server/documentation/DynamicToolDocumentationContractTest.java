package com.cmagent.server.documentation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolDocumentationContractTest {

    @Test
    void 动态HTTP工具和MCP文档覆盖生产安全与调用契约() throws IOException {
        String readme = readRepositoryFile("README.md");
        String configuration = readRepositoryFile("docs/configuration.md");
        String operations = readRepositoryFile("docs/operations.md");

        assertThat(readme).contains("动态 HTTP 工具", "控制台", "MCP");
        assertThat(configuration).contains(
                "JSON Pointer", "secret/...", "allowed-hosts", "cm-agent.mcp.enabled",
                "/mcp", "tool:mcp:invoke", "tool:debug", "GET", "405",
                "响应上限", "重定向"
        );
        assertThat(operations).contains(
                "幂等", "MCP", "HTTP 工具", "SecretProvider", "egress"
        );
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
