package com.cmagent.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleResourceTest {

    @Test
    void 控制台包含独立登录页和全部管理模块() throws IOException {
        String html = resource("META-INF/resources/index.html");

        assertThat(html)
                .contains("CM Agent 控制台")
                .contains("id=\"loginView\"", "id=\"consoleView\"", "id=\"loginForm\"")
                .contains("id=\"overviewPage\"", "id=\"agentsPage\"", "id=\"toolsPage\"")
                .contains("id=\"runsPage\"", "id=\"auditPage\"", "id=\"sidebarNav\"")
                .contains("/assets/console-core.js", "/assets/app.js")
                .doesNotContain("localStorage", "sessionStorage");
    }

    @Test
    void 控制台样式包含响应式和键盘焦点规则() throws IOException {
        String css = resource("META-INF/resources/assets/styles.css");

        assertThat(css)
                .contains(".sidebar", ".page-view", ".empty-state", ":focus-visible")
                .contains("@media (max-width: 900px)");
    }

    @Test
    void 控制台核心脚本可独立发布且不持久化令牌() throws IOException {
        String script = resource("META-INF/resources/assets/console-core.js");

        assertThat(script)
                .contains("CmAgentConsoleCore", "createApiClient", "appendCursorPage")
                .doesNotContain("localStorage", "sessionStorage");
    }

    @Test
    void 控制台编排认证和资源管理且安全渲染动态文本() throws IOException {
        String html = resource("META-INF/resources/index.html");
        String script = resource("META-INF/resources/assets/app.js");

        assertThat(html).contains(
                "id=\"agentForm\"", "id=\"agentList\"", "id=\"agentDetail\"",
                "id=\"toolForm\"", "id=\"toolList\"", "id=\"grantForm\"",
                "id=\"overviewAgentCount\"", "id=\"overviewToolCount\""
        );
        assertThat(script)
                .contains("/api/auth/login", "/api/auth/me", "/api/agents", "/api/tools")
                .contains("loadInitialData", "logout", "resetSessionViews", "textContent")
                .doesNotContain("localStorage", "sessionStorage", ".innerHTML");
    }

    @Test
    void 控制台展示运行详情和审计游标分页() throws IOException {
        String html = resource("META-INF/resources/index.html");
        String script = resource("META-INF/resources/assets/app.js");

        assertThat(html).contains(
                "id=\"runAgentSelect\"", "id=\"runForm\"", "id=\"runList\"",
                "id=\"runDetail\"", "id=\"runToolCalls\"", "id=\"loadMoreRunsBtn\"",
                "id=\"auditList\"", "id=\"loadMoreAuditBtn\""
        );
        assertThat(script)
                .contains("/runs", "/api/audit-events", "nextCursor")
                .contains("loadRunDetail", "loadAudit")
                .doesNotContain(".innerHTML");
    }

    @Test
    void Tool类型选项与后端枚举保持一致() throws IOException {
        String html = resource("META-INF/resources/index.html");

        assertThat(html)
                .contains("value=\"LOCAL\"", "value=\"MCP\"", "value=\"A2A\"", "value=\"HTTP\"");
    }

    @Test
    void 控制台提供HTTP配置MCP发布和受控调试入口() throws IOException {
        String html = resource("META-INF/resources/index.html");
        String core = resource("META-INF/resources/assets/console-core.js");
        String script = resource("META-INF/resources/assets/app.js");

        assertThat(html).contains(
                "id=\"httpConfigFields\"", "id=\"httpInputSchema\"", "id=\"httpParameterMappings\"",
                "id=\"httpSecretHeaders\"", "Secret 引用", "id=\"toolMcpPublished\"",
                "id=\"debugToolForm\"", "id=\"debugInput\"", "id=\"debugResult\""
        ).contains("id=\"httpUrlTemplate\" type=\"text\"")
                .doesNotContain("id=\"httpUrlTemplate\" type=\"url\"");
        assertThat(core).contains(
                "parseJsonField", "canDebugTool", "buildHttpToolPayload",
                "createToolPublicationLock", "createLoadRevisionGate"
        );
        assertThat(script).contains(
                "/debug", "/mcp-publication", "publishMcpTool", "unpublishMcpTool", "debugTool",
                "textContent", "canDebugTool", "toolPublicationLock", "toolLoadRevision",
                "publicationButton.disabled", "invalidate()"
        ).doesNotContain(".innerHTML", "localStorage", "sessionStorage");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("资源应存在：%s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
