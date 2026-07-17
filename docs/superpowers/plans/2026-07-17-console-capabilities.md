# CM Agent 可操作管理控制台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有轻量控制台升级为覆盖已交付后端接口的可操作管理控制台。

**Architecture:** 保持 `cm-agent-console` 原生 HTML/CSS/JavaScript 单页交付方式。HTML 提供独立登录页与控制台模块结构，`console-core.js` 提供可在浏览器和 Node 中复用的请求、分页及格式化逻辑，`app.js` 负责 DOM 渲染和业务编排；不修改后端业务接口。

**Tech Stack:** Java 21、Maven 3.9+、JUnit Jupiter 5.12.2、AssertJ 3.27.3、原生 HTML/CSS/JavaScript、Node.js 内置 `node:test`。

## Global Constraints

- 项目代码、测试说明和生产文档中的新增文字使用中文。
- 不新增或修改后端业务接口，不展示编辑、删除、手动取消、流式输出、多轮会话或 HITL。
- 不引入 React、Vue、模板引擎、第三方前端依赖或 Node 前端构建链。
- JWT 只保存在页面内存中；不得持久化 JWT、用户名或密码。
- 所有接口返回文本必须通过 `textContent` 或显式文本节点渲染，不使用接口数据拼接 `innerHTML`。
- 不展示 JWT、密码、模型 API Key、数据库凭据、完整 JDBC URL、底层 SQL 或堆栈。
- 保持现有用户配置改动和 `.bak` 文件不动，不纳入任何提交。
- 涉及 `ConsoleSmokeTest` 的 Testcontainers 验证必须在 `ssh rocky` 的容器环境执行；普通 console 模块测试在本机 JDK 21 环境执行。

---

### Task 1: 建立控制台资源契约与页面骨架

**Files:**
- Modify: `cm-agent-console/pom.xml`
- Create: `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/index.html`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`

**Interfaces:**
- Consumes: Spring Boot 依赖管理中的 JUnit Jupiter 和 AssertJ 版本。
- Produces: 页面元素 ID `loginView`、`consoleView`、`loginForm`、`globalStatus`、`sidebarNav`、`overviewPage`、`agentsPage`、`toolsPage`、`runsPage`、`auditPage`；Task 3 和 Task 4 的脚本依赖这些 ID。

- [ ] **Step 1: 在 console 模块加入测试依赖**

在 `cm-agent-console/pom.xml` 的 `artifactId` 后加入：

```xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 编写页面资源失败测试**

创建 `ConsoleResourceTest.java`，从 classpath 读取资源并断言登录视图、导航、五个页面、核心脚本和安全约束：

```java
package com.cmagent.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleResourceTest {

    @Test
    void 控制台包含独立登录页和全部管理模块() throws IOException {
        String html = resource("META-INF/resources/index.html");

        assertThat(html)
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

    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("资源应存在：%s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 3: 运行测试并确认因新结构缺失而失败**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，提示 `loginView`、`consoleView` 或 `.sidebar` 等新结构不存在。

- [ ] **Step 4: 实现最小页面骨架**

重写 `index.html`：

```html
<body>
<section id="loginView" class="login-view" aria-labelledby="loginTitle">
    <form id="loginForm" class="login-card">
        <p class="eyebrow">CM AGENT</p>
        <h1 id="loginTitle">登录管理控制台</h1>
        <label for="loginUsername">用户名</label>
        <input id="loginUsername" name="username" autocomplete="username" required>
        <label for="loginPassword">密码</label>
        <input id="loginPassword" name="password" type="password" autocomplete="current-password" required>
        <button id="loginBtn" class="button primary" type="submit">登录控制台</button>
        <p id="loginStatus" class="form-status" aria-live="polite"></p>
    </form>
</section>
<div id="consoleView" class="console-layout" hidden>
    <aside class="sidebar">
        <a class="brand" href="#" aria-label="CM Agent 能力总览">CM Agent</a>
        <nav id="sidebarNav" aria-label="控制台导航">
            <button data-page="overviewPage">能力总览</button>
            <button data-page="agentsPage">Agent 管理</button>
            <button data-page="toolsPage">工具治理</button>
            <button data-page="runsPage">运行记录</button>
            <button data-page="auditPage">审计日志</button>
        </nav>
        <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">OpenAPI</a>
        <a href="/actuator/health" target="_blank" rel="noreferrer">健康检查</a>
    </aside>
    <main class="workspace">
        <header class="workspace-header">
            <div><h1 id="pageTitle">能力总览</h1><p id="pageSubtitle"></p></div>
            <div><span id="currentUser"></span><button id="logoutBtn">退出</button></div>
        </header>
        <p id="globalStatus" class="global-status" aria-live="polite"></p>
        <section id="overviewPage" class="page-view"></section>
        <section id="agentsPage" class="page-view" hidden></section>
        <section id="toolsPage" class="page-view" hidden></section>
        <section id="runsPage" class="page-view" hidden></section>
        <section id="auditPage" class="page-view" hidden></section>
    </main>
</div>
<script src="/assets/console-core.js"></script>
<script src="/assets/app.js"></script>
</body>
```

将各模块所需的列表、空状态、详情面板和表单作为对应 section 的静态子结构补齐。表单字段使用稳定 ID，分别为 `agentForm`、`toolForm`、`grantForm`、`runForm`，分页按钮为 `loadMoreRunsBtn` 和 `loadMoreAuditBtn`。

重写 `styles.css`，定义颜色变量、`.login-view`、`.login-card`、`.console-layout`、`.sidebar`、`.workspace`、`.page-view`、`.stats-grid`、`.data-table`、`.detail-panel`、`.empty-state`、`.status-badge`、按钮禁用态和 `:focus-visible`。在 `@media (max-width: 900px)` 中将布局改为单列、导航可横向滚动、卡片单列和表格容器横向滚动。

- [ ] **Step 5: 运行资源测试并确认通过**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS，`ConsoleResourceTest` 2 个测试通过。

- [ ] **Step 6: 提交页面骨架**

```powershell
git add -- cm-agent-console/pom.xml cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java cm-agent-console/src/main/resources/META-INF/resources/index.html cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css
git commit -m "feat: 建立管理控制台页面骨架"
```

### Task 2: 建立可测试的前端核心逻辑

**Files:**
- Create: `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- Create: `cm-agent-console/src/test/js/console-core.test.cjs`
- Modify: `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`

**Interfaces:**
- Produces: 全局对象和 CommonJS 导出 `CmAgentConsoleCore`。
- Produces: `formatError(status, body, fallbackText)`、`createApiClient(options)`、`appendCursorPage(currentItems, page)`、`formatDateTime(value)`、`statusMeta(status)`。
- Consumes: `createApiClient` 的 `fetchImpl`、`getToken` 和 `onUnauthorized` 注入函数。

- [ ] **Step 1: 编写核心逻辑失败测试**

创建 `console-core.test.cjs`：

```javascript
const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

test("优先显示结构化接口错误", () => {
    assert.equal(core.formatError(403, {message: "没有权限"}, ""), "请求失败(403)：没有权限");
});

test("追加游标页并保留下一游标", () => {
    const result = core.appendCursorPage([{id: "1"}], {items: [{id: "2"}], nextCursor: "next"});
    assert.deepEqual(result, {items: [{id: "1"}, {id: "2"}], nextCursor: "next"});
});

test("401 会通知认证失效且不暴露响应体", async () => {
    let unauthorized = false;
    const api = core.createApiClient({
        fetchImpl: async () => ({ok: false, status: 401, headers: {get: () => "application/json"}, text: async () => '{"message":"令牌无效"}'}),
        getToken: () => "secret-token",
        onUnauthorized: () => { unauthorized = true; }
    });

    await assert.rejects(() => api.request("/api/auth/me"), /未登录或令牌已失效/);
    assert.equal(unauthorized, true);
});

test("请求自动附加 Bearer 令牌", async () => {
    let authorization = "";
    const api = core.createApiClient({
        fetchImpl: async (_path, options) => {
            authorization = options.headers.get("Authorization");
            return {ok: true, status: 200, headers: {get: () => "application/json"}, text: async () => "[]"};
        },
        getToken: () => "memory-only-token",
        onUnauthorized: () => {}
    });

    await api.request("/api/agents");
    assert.equal(authorization, "Bearer memory-only-token");
});
```

- [ ] **Step 2: 运行 Node 测试并确认模块缺失失败**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: FAIL，提示找不到 `console-core.js`。

- [ ] **Step 3: 实现最小核心模块**

创建 UMD 风格的 `console-core.js`，在浏览器挂到 `window.CmAgentConsoleCore`，在 Node 使用 `module.exports`。实现：

```javascript
(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentConsoleCore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    function formatError(status, body, fallbackText) {
        const message = body && typeof body === "object" && (body.message || body.error || body.detail);
        const readable = message || (typeof body === "string" && body.trim()) || (fallbackText && fallbackText.trim());
        return readable ? `请求失败(${status})：${readable}` : `请求失败(${status})：服务器未返回可读错误信息`;
    }

    function appendCursorPage(currentItems, page) {
        return {items: [...currentItems, ...(page?.items || [])], nextCursor: page?.nextCursor || ""};
    }

    function createApiClient({fetchImpl, getToken, onUnauthorized}) {
        return {request};
        async function request(path, options = {}) {
            const headers = new Headers(options.headers || {});
            headers.set("Content-Type", "application/json");
            const token = getToken();
            if (token) headers.set("Authorization", `Bearer ${token}`);
            const response = await fetchImpl(path, {...options, headers});
            const rawBody = await response.text();
            let body = null;
            try { body = rawBody ? JSON.parse(rawBody) : null; } catch { body = rawBody; }
            if (!response.ok) {
                if (response.status === 401) {
                    onUnauthorized();
                    throw new Error("未登录或令牌已失效，请重新登录。");
                }
                throw new Error(formatError(response.status, body, rawBody));
            }
            return body;
        }
    }

    function formatDateTime(value) {
        if (!value) return "—";
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("zh-CN", {
            year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", second: "2-digit"
        }).format(date);
    }

    function statusMeta(status) {
        const values = {
            SUCCEEDED: {label: "成功", tone: "success"},
            RUNNING: {label: "运行中", tone: "warning"},
            FAILED: {label: "失败", tone: "error"}
        };
        return values[status] || {label: status || "未知", tone: "neutral"};
    }
    return {formatError, createApiClient, appendCursorPage, formatDateTime, statusMeta};
});
```

将 `formatDateTime` 和 `statusMeta` 的明确用例加入同一测试文件：无日期返回 `—`；`SUCCEEDED`、`RUNNING`、`FAILED` 分别返回成功、运行中、失败及对应 tone。

- [ ] **Step 4: 运行 Node 测试并确认通过**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: PASS，全部核心逻辑测试通过。

- [ ] **Step 5: 扩展资源测试以确认核心脚本可发布**

在 `ConsoleResourceTest` 增加读取 `console-core.js` 的测试，断言包含 `CmAgentConsoleCore`、`createApiClient`、`appendCursorPage`，且不包含 `localStorage`、`sessionStorage`。

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

- [ ] **Step 6: 提交核心逻辑**

```powershell
git add -- cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java
git commit -m "feat: 增加控制台前端核心逻辑"
```

### Task 3: 实现认证、总览、Agent 与 Tool 管理

**Files:**
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/index.html`
- Modify: `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`

**Interfaces:**
- Consumes: `CmAgentConsoleCore.createApiClient`、`formatDateTime`、`statusMeta`。
- Produces: 内存状态 `token`、`currentUser`、`agents`、`tools`、`selectedAgentId`、`selectedToolId`。
- Produces: `login()`、`logout()`、`navigate(pageId)`、`loadInitialData()`、`loadAgents()`、`loadTools()`、`createAgent()`、`createTool()`、`grantTool()`。

- [ ] **Step 1: 扩展资源失败测试**

在 `ConsoleResourceTest` 增加测试，读取 `index.html` 和 `app.js`，断言：

```java
assertThat(html).contains(
        "id=\"agentForm\"", "id=\"agentList\"", "id=\"agentDetail\"",
        "id=\"toolForm\"", "id=\"toolList\"", "id=\"grantForm\"",
        "id=\"overviewAgentCount\"", "id=\"overviewToolCount\""
);
assertThat(script)
        .contains("/api/auth/login", "/api/auth/me", "/api/agents", "/api/tools")
        .contains("textContent")
        .doesNotContain("localStorage", "sessionStorage", ".innerHTML");
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，提示管理表单、统计元素或新接口编排缺失。

- [ ] **Step 3: 实现认证与导航**

在 `app.js` 初始化单一状态对象，通过 `createApiClient` 注入 `window.fetch.bind(window)`、令牌读取函数和 `logout` 回调。登录流程必须：校验非空输入、禁用按钮、调用登录接口、仅把 `accessToken` 写入内存、调用 `/api/auth/me`、切换视图并加载初始数据。退出和 `401` 必须清空全部状态、密码输入和页面数据。

导航事件读取 `data-page`，只显示选中 `.page-view`，同步 `aria-current`、标题和说明。不得修改浏览器地址。

- [ ] **Step 4: 实现总览、Agent 与 Tool 操作**

使用 `document.createElement`、`textContent` 和 `replaceChildren` 渲染列表：

- `GET /api/agents` 刷新 Agent 列表和计数；创建成功后选择新 Agent。
- `GET /api/agents/{id}` 加载详情。
- `POST /api/agents` 发送 `name`、`systemPrompt`、`modelName`。
- `GET /api/tools` 刷新 Tool 列表和计数。
- `POST /api/tools` 发送 `name`、`description`、`type`、`riskLevel`。
- `POST /api/tools/{id}/grants` 发送 `agentId`。

所有提交使用统一 `withSubmitState(button, action)` 禁止重复提交，并通过 `setStatus(element, message, tone)` 给出成功或失败文本。空列表使用静态 `.empty-state`，不伪造数据。

- [ ] **Step 5: 运行 console 模块与 Node 测试**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: PASS。

- [ ] **Step 6: 提交管理能力**

```powershell
git add -- cm-agent-console/src/main/resources/META-INF/resources/index.html cm-agent-console/src/main/resources/META-INF/resources/assets/app.js cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java
git commit -m "feat: 实现控制台认证与资源管理"
```

### Task 4: 实现运行记录、详情与审计游标

**Files:**
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/index.html`
- Modify: `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- Modify: `cm-agent-console/src/test/js/console-core.test.cjs`

**Interfaces:**
- Consumes: `appendCursorPage`、`formatDateTime`、`statusMeta`。
- Produces: 状态 `runs`、`runCursor`、`auditEvents`、`auditCursor`、`selectedRunId`。
- Produces: `runAgent()`、`loadRuns({append})`、`loadRunDetail(runId)`、`loadAudit({append})`。

- [ ] **Step 1: 编写运行与审计资源失败测试**

扩展 `ConsoleResourceTest`：

```java
assertThat(html).contains(
        "id=\"runAgentSelect\"", "id=\"runForm\"", "id=\"runList\"",
        "id=\"runDetail\"", "id=\"runToolCalls\"", "id=\"loadMoreRunsBtn\"",
        "id=\"auditList\"", "id=\"loadMoreAuditBtn\""
);
assertThat(script)
        .contains("/runs", "/api/audit-events", "nextCursor")
        .doesNotContain(".innerHTML");
```

在 Node 测试增加“新查询替换列表”“加载更多追加列表”“缺少 items 时使用空数组”的 `appendCursorPage` 边界断言。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，提示运行或审计结构与编排缺失。

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: FAIL，提示新增分页行为尚未满足。

- [ ] **Step 3: 实现运行执行、分页与详情**

- Agent 选择变化时清空 `runs`、`runCursor` 和详情，再调用 `GET /api/agents/{agentId}/runs?limit=20`。
- 加载更多时对 `cursor` 使用 `encodeURIComponent`，通过 `appendCursorPage` 追加结果。
- `POST /api/agents/{agentId}/runs` 成功后显示结果，重新加载第一页并打开对应运行详情。
- `GET /api/agents/{agentId}/runs/{runId}` 展示 run 的输入、输出、错误、状态、开始/结束时间及 `toolCalls`。
- 没有 `nextCursor` 时隐藏加载更多按钮并显示“已加载全部”。

- [ ] **Step 4: 实现审计游标加载**

首次进入审计页调用 `GET /api/audit-events?limit=20`。加载更多时追加 `cursor`。每行展示 `eventType`、`resourceType`、`resourceId`、`status`、`principalId`、`message` 和 `createdAt`。所有字段通过文本节点渲染；空值显示 `—`。

- [ ] **Step 5: 运行 console 模块与 Node 测试**

Run: `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: PASS。

- [ ] **Step 6: 提交运行与审计能力**

```powershell
git add -- cm-agent-console/src/main/resources/META-INF/resources/index.html cm-agent-console/src/main/resources/META-INF/resources/assets/app.js cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java cm-agent-console/src/test/js/console-core.test.cjs
git commit -m "feat: 展示运行历史与审计日志"
```

### Task 5: 更新生产文档并完成验证

**Files:**
- Modify: `README.md`
- Modify: `docs/release-notes.md`
- Verify: `cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java`

**Interfaces:**
- Consumes: Tasks 1–4 的最终页面和脚本。
- Produces: 用户可见的控制台能力说明、限制和发布记录。

- [ ] **Step 1: 更新 README 控制台说明**

在“服务启动后访问”附近补充：控制台提供独立登录、能力总览、Agent/Tool 管理、运行调试与历史详情、审计分页；仅展示现有后端能力，JWT 不持久化，刷新后需要重新登录。

- [ ] **Step 2: 更新发布说明**

在 `docs/release-notes.md` 当前版本段落记录管理控制台的信息架构、接口覆盖、响应式与安全渲染变化，并明确没有新增后端业务接口。

- [ ] **Step 3: 检查本机 Java 与 Maven 环境**

Run: `java -version`

Expected: Java 21。

Run: `mvn -v`

Expected: Maven 3.9+ 且运行在 Java 21。

- [ ] **Step 4: 运行本机前端核心与 console 模块测试**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: PASS，0 failure。

Run: `mvn -q -pl cm-agent-console -am test`

Expected: BUILD SUCCESS，0 failure。

- [ ] **Step 5: 运行服务端非容器测试与打包**

Run: `mvn -q -pl cm-agent-server -am "-Dtest=AuthControllerTest,RunControllerTest,ApiExceptionHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: BUILD SUCCESS；上游模块因没有匹配类而跳过测试，不执行任何 Testcontainers 测试类。

Run: `mvn -q "-DskipTests" package`

Expected: BUILD SUCCESS。

- [ ] **Step 6: 在 Rocky Linux 容器环境验证 ConsoleSmokeTest**

通过 `ssh rocky` 检查 Docker 可用、远程仓库提交与本地待验证提交一致，再使用 `maven:3.9.9-eclipse-temurin-21` 执行：

```bash
mvn -q -pl cm-agent-server -am -Dtest=ConsoleSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Testcontainers 使用 PostgreSQL `16-alpine`，`ConsoleSmokeTest` 全部通过。若远程工作区提交不同或 SSH/Docker 不可用，停止容器验证并在最终报告中说明原因，不在本机 Docker Desktop 代跑。

- [ ] **Step 7: 检查敏感信息、差异和仓库状态**

Run: `rg -n "accessToken\s*[:=]\s*['\"]|localStorage|sessionStorage|jdbc:(postgresql|mysql)://|api[_-]?key\s*[:=]" cm-agent-console README.md docs/release-notes.md`

Expected: 只允许接口字段名 `accessToken` 和说明性文字；无硬编码令牌、凭据或持久化调用。

Run: `git diff --check`

Expected: 无空白错误。

- [ ] **Step 8: 提交文档并记录验证证据**

```powershell
git add -- README.md docs/release-notes.md
git commit -m "docs: 更新管理控制台使用说明"
```

最终报告按仓库 `AGENTS.md` 顺序列出变更摘要、实际验证命令与结果、影响范围、风险与注意事项、后续建议。
