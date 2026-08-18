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
     * 验证 v2 使用带版本号的独立页面，并且每个管理页面只携带自身业务区域。
     */
    void v2控制台按版本号拆分为独立页面() throws IOException {
        String login = resource("META-INF/resources/console/v2/login.html");
        String overview = resource("META-INF/resources/console/v2/overview.html");
        String agents = resource("META-INF/resources/console/v2/agents.html");
        String tools = resource("META-INF/resources/console/v2/tools.html");
        String runs = resource("META-INF/resources/console/v2/runs.html");
        String audit = resource("META-INF/resources/console/v2/audit.html");

        assertThat(login)
                .contains("data-console-version=\"v2\"", "id=\"loginForm\"", "/console/v1/")
                .doesNotContain("id=\"overviewPage\"", "id=\"agentsPage\"");
        assertThat(overview)
                .contains("data-page=\"overviewPage\"", "id=\"overviewPage\"")
                .doesNotContain("id=\"agentsPage\"", "id=\"toolsPage\"", "id=\"runsPage\"", "id=\"auditPage\"");
        assertThat(agents)
                .contains("data-page=\"agentsPage\"", "id=\"agentsPage\"", "id=\"agentForm\"")
                .doesNotContain("id=\"toolsPage\"", "id=\"runsPage\"", "id=\"auditPage\"");
        assertThat(tools)
                .contains("data-page=\"toolsPage\"", "id=\"toolsPage\"", "id=\"toolForm\"", "id=\"debugToolForm\"")
                .doesNotContain("id=\"agentsPage\"", "id=\"runsPage\"", "id=\"auditPage\"");
        assertThat(runs)
                .contains("data-page=\"runsPage\"", "id=\"runsPage\"", "id=\"runForm\"")
                .doesNotContain("id=\"agentsPage\"", "id=\"toolsPage\"", "id=\"auditPage\"");
        assertThat(audit)
                .contains("data-page=\"auditPage\"", "id=\"auditPage\"", "id=\"auditList\"")
                .doesNotContain("id=\"agentsPage\"", "id=\"toolsPage\"", "id=\"runsPage\"");
    }

    @Test
    /**
     * 验证 v2 以服务端 HttpOnly Cookie 为主，并使用不持久化的内存令牌兼容受限浏览器。
     */
    void v2跨页会话不在浏览器脚本中存储令牌() throws IOException {
        String app = resource("META-INF/resources/assets/app.js");
        String[] pages = {
                "login.html", "overview.html", "agents.html", "tools.html", "runs.html", "audit.html"
        };

        assertThat(app)
                .contains("loadCurrentPage", "/console/v2/login.html", "/api/auth/logout", "clearServerSession")
                .contains("state.token = accessToken", "loadMultiPage", "new DOMParser()", "pushState")
                .contains("button[data-page]", "button[data-navigate]")
                .doesNotContain("document.querySelectorAll(\"[data-page]\")")
                .doesNotContain("CmAgentConsoleSession", "sessionStorage", "localStorage");
        for (String page : pages) {
            assertThat(resource("META-INF/resources/console/v2/" + page))
                    .contains("/assets/app.js?v=2.0.7", "/assets/console-core.js?v=2.0.7")
                    .doesNotContain("session.js", "sessionStorage", "localStorage");
        }
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 控制台样式包含响应式和键盘焦点规则() throws IOException {
        String css = resource("META-INF/resources/assets/styles.css");

        assertThat(css)
                .contains(".sidebar", ".page-view", ".empty-state", ":focus-visible")
                .contains("#toolsPage .management-grid { grid-template-columns: minmax(0, 1fr); }")
                .contains("#toolsPage .management-grid > * { width: 100%; grid-column: 1; }")
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
                "id=\"httpConfigFields\"", "id=\"httpParameterEditor\"", "id=\"httpParameterList\"",
                "id=\"httpSecretHeaders\"", "Secret 引用", "id=\"toolMcpPublished\"",
                "id=\"debugToolForm\"", "id=\"debugInput\"", "id=\"debugResult\""
        ).doesNotContain("httpInputSchema", "httpParameterMappings")
                .contains("id=\"httpUrlTemplate\" type=\"text\"")
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
     * 验证 HTTP Tool 表单提供树形参数录入、扁平提交、根数组说明和安全提醒。
     */
    void HTTP工具表单提供分步样例和一键填入能力() throws IOException {
        String html = resource("META-INF/resources/index.html");
        String css = resource("META-INF/resources/assets/styles.css");
        String script = resource("META-INF/resources/assets/app.js");

        assertThat(html).contains(
                "id=\"fillHttpExampleBtn\"", "HTTP 配置填写指南", "查看地址样例",
                "id=\"httpParameterEditor\"", "id=\"httpParameterList\"", "id=\"addHttpParameterBtn\"",
                "页面按树形结构录入参数", "接口没有输入参数时可以保持为空", "添加顶层参数",
                "OBJECT 或 ARRAY 节点内添加子参数", "当前工具没有输入参数，可直接保存",
                "根数组", "查看 Secret 引用样例",
                "请勿填写、粘贴或展示 Token、API Key 等真实 Secret 值"
        );
        assertThat(css).contains(
                ".http-config-guide", ".http-form-field", ".field-step", ".field-example",
                ".parameter-card", ".parameter-grid", ".parameter-tree-node", ".parameter-children"
        );
        assertThat(script).contains(
                "HTTP_TOOL_FORM_EXAMPLE", "fillHttpToolExample", "api.example.com/orders/{orderId}",
                "secret/integration/orders-token", "addHttpParameter", "addHttpParameterChild",
                "renderHttpParameterTree", "collectHttpParameters",
                "BODY_ROOT", "填入示例会覆盖当前 HTTP 配置"
        ).doesNotContain(".innerHTML");
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
