package com.cmagent.server.runtime;

import io.agentscope.core.tool.ToolValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClientToolCatalogTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void 读取文件Schema不使用oneOf且接受数字字符串参数() {
        String schema = LocalClientToolCatalog.definitions(TENANT_ID).stream()
                .filter(tool -> tool.name().equals("local_client_read_files"))
                .findFirst()
                .orElseThrow()
                .inputSchema();

        assertThat(schema).doesNotContain("\"oneOf\"");
        assertThat(ToolValidator.validateInput(schema, Map.of(
                "action", "list",
                "path", "C:\\work",
                "limit", "50"))).isNull();
        assertThat(ToolValidator.validateInput(schema, Map.of(
                "action", "read",
                "path", "C:\\work\\README.md",
                "offset", "0",
                "limit", "100"))).isNull();
    }

    @Test
    void 变更文件Schema不使用oneOf且接受追加大小字符串() {
        String schema = LocalClientToolCatalog.definitions(TENANT_ID).stream()
                .filter(tool -> tool.name().equals("local_client_change_files"))
                .findFirst()
                .orElseThrow()
                .inputSchema();

        assertThat(schema).doesNotContain("\"oneOf\"");
        assertThat(ToolValidator.validateInput(schema, Map.of(
                "action", "create",
                "path", "C:\\work\\new.txt",
                "contentBase64", ""))).isNull();
        assertThat(ToolValidator.validateInput(schema, Map.of(
                "action", "append",
                "path", "C:\\work\\new.txt",
                "expectedSize", "3",
                "contentBase64", "ZGVm"))).isNull();
        assertThat(ToolValidator.validateInput(schema, Map.of(
                "action", "delete",
                "path", "C:\\work\\new.txt"))).isNull();
    }
}
