package com.cmagent.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleResourceTest {

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
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
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 控制台样式包含响应式和键盘焦点规则() throws IOException {
        String css = resource("META-INF/resources/assets/styles.css");

        assertThat(css)
                .contains(".sidebar", ".page-view", ".empty-state", ":focus-visible")
                .contains("@media (max-width: 900px)");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 控制台核心脚本可独立发布且不持久化令牌() throws IOException {
        String script = resource("META-INF/resources/assets/console-core.js");

        assertThat(script)
                .contains("CmAgentConsoleCore", "createApiClient", "appendCursorPage")
                .doesNotContain("localStorage", "sessionStorage");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
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
    /**
     * 验证方法名称所描述的业务行为。
     */
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
    /**
     * 验证方法名称所描述的业务行为。
     */
    void Tool类型选项与后端枚举保持一致() throws IOException {
        String html = resource("META-INF/resources/index.html");

        assertThat(html)
                .contains("value=\"LOCAL\"", "value=\"MCP\"", "value=\"A2A\"", "value=\"HTTP\"");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
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
                "publicationButton.disabled", "completeWrite()",
                "tool.type === \"HTTP\" || tool.type === \"LOCAL\""
        ).doesNotContain(".innerHTML", "localStorage", "sessionStorage");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 控制台提供内置Local示例安装和运行时就绪提示() throws IOException {
        String html = resource("META-INF/resources/index.html");
        String core = resource("META-INF/resources/assets/console-core.js");
        String script = resource("META-INF/resources/assets/app.js");

        assertThat(html).contains(
                "id=\"localExampleSection\"", "id=\"localExampleList\"", "id=\"localExampleStatus\"",
                "普通 LOCAL 工具表单只保存治理元数据"
        );
        assertThat(core).contains(
                "buildLocalExampleInstallPath", "formatJsonInput", "runtimeReady", "getSessionEpoch"
        );
        assertThat(script).contains(
                "/api/tools/local-examples", "loadLocalExamples", "installLocalExample",
                "调用/调试", "未注册执行器", "getSessionEpoch: () => sessionEpoch.capture()"
        ).doesNotContain(".innerHTML");
    }

    /**
     * 验证或支持 {@code resource} 所描述的测试场景。
     *
     * @param path 测试辅助方法使用的 path 参数
     */
    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("资源应存在：%s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
