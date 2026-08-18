(function () {
    "use strict";

    const core = window.CmAgentConsoleCore;
    if (!core) {
        throw new Error("控制台核心脚本未加载");
    }

    const isMultiPage = document.body.dataset.consoleVersion === "v2";
    let currentPage = document.body.dataset.page || "";
    const multiPagePaths = Object.freeze({
        overviewPage: "/console/v2/overview.html",
        agentsPage: "/console/v2/agents.html",
        toolsPage: "/console/v2/tools.html",
        runsPage: "/console/v2/runs.html",
        auditPage: "/console/v2/audit.html"
    });

    const HTTP_TOOL_FORM_EXAMPLE = Object.freeze({
        method: "POST",
        urlTemplate: "https://api.example.com/orders/{orderId}",
        parameters: [
            {id: "orderId", parentId: null, name: "orderId", dataType: "STRING", requestLocation: "PATH", description: "订单编号", required: true, exampleValue: "A1001"},
            {id: "status", parentId: null, name: "status", dataType: "STRING", requestLocation: "QUERY", description: "订单状态", required: false, defaultValue: "OPEN", enumValues: ["OPEN", "CLOSED"]},
            {id: "payload", parentId: null, name: "payload", dataType: "ARRAY", requestLocation: "BODY_ROOT", description: "请求体数组", required: true, minItems: 1},
            {id: "payloadItem", parentId: "payload", name: null, dataType: "OBJECT", requestLocation: null, description: "单条请求数据", required: false},
            {id: "p1", parentId: "payloadItem", name: "p1", dataType: "STRING", requestLocation: null, description: "参数一", required: true, exampleValue: "v1"},
            {id: "enabled", parentId: "payloadItem", name: "enabled", dataType: "BOOLEAN", requestLocation: null, description: "是否启用", required: false, defaultValue: false}
        ],
        secretHeaders: {Authorization: "secret/integration/orders-token"},
        timeoutMillis: 3000
    });

    const state = {
        token: "",
        currentUser: null,
        agents: [],
        tools: [],
        localExamples: [],
        selectedAgentId: "",
        selectedAgent: null,
        selectedToolId: "",
        editingToolId: "",
        runs: [],
        runCursor: "",
        selectedRunId: "",
        auditEvents: [],
        auditCursor: ""
    };
    const toolPublicationLock = core.createToolPublicationLock();
    const toolLoadRevision = core.createLoadRevisionGate();
    const agentDetailRevision = core.createLoadRevisionGate();
    const localExampleInstallLock = core.createToolPublicationLock();
    const localExampleLoadRevision = core.createLoadRevisionGate();
    const localExampleInstallRevision = core.createKeyedLoadRevisionGate();
    const sessionEpoch = core.createSessionEpochGate();
    const submitStateGuard = core.createSubmitStateGuard();
    let pageNavigationRevision = 0;

    const pageInfo = {
        overviewPage: ["能力总览", "查看当前租户已交付的 Agent 能力与最近活动。"],
        agentsPage: ["Agent 管理", "创建 Agent，并查看模型、提示词与工具授权信息。"],
        toolsPage: ["工具治理", "注册 Tool，并向指定 Agent 授予使用权限。"],
        runsPage: ["运行记录", "执行 Agent，并查看运行历史、结果与工具调用。"],
        auditPage: ["审计日志", "追踪当前租户的安全事件和资源操作。"]
    };

    const $ = (id) => document.getElementById(id);
    const api = core.createApiClient({
        fetchImpl: window.fetch.bind(window),
        getToken: () => state.token,
        getSessionEpoch: () => sessionEpoch.capture(),
        onUnauthorized: () => logout("登录状态已失效，请重新登录。")
    });

    function setStatus(element, message = "", tone = "neutral") {
        if (!element) return;
        element.textContent = message;
        element.dataset.tone = tone;
        if (element === $("globalStatus")) {
            element.hidden = !message;
        }
    }

    function element(tagName, options = {}) {
        const node = document.createElement(tagName);
        if (options.className) node.className = options.className;
        if (options.text !== undefined) node.textContent = String(options.text);
        if (options.type) node.type = options.type;
        return node;
    }

    async function withSubmitState(button, action) {
        const originalText = button.textContent;
        const pendingText = "处理中…";
        const ticket = submitStateGuard.begin(button, sessionEpoch.capture());
        button.disabled = true;
        button.textContent = pendingText;
        try {
            return await action();
        } finally {
            if (!submitStateGuard.finish(ticket, sessionEpoch.capture())) return;
            button.disabled = false;
            if (button.textContent === pendingText) button.textContent = originalText;
        }
    }

    async function login() {
        const loginSession = sessionEpoch.capture();
        const username = $("loginUsername").value.trim();
        const password = $("loginPassword").value;
        if (!username || !password) {
            setStatus($("loginStatus"), "请输入用户名和密码。", "error");
            return;
        }

        setStatus($("loginStatus"), "正在验证身份…", "neutral");
        try {
            await withSubmitState($("loginBtn"), async () => {
                const result = await api.request("/api/auth/login", {
                    method: "POST",
                    body: JSON.stringify({username, password})
                });
                if (!sessionEpoch.isCurrent(loginSession)) return;
                const accessToken = result?.accessToken || "";
                if (!accessToken) throw new Error("登录响应未包含访问令牌。");
                // v2 仍以 HttpOnly Cookie 为跨文档会话主链路；内存令牌只用于兼容会隔离跨文档 Cookie 的嵌入式浏览器。
                state.token = accessToken;
                state.currentUser = await api.request("/api/auth/me");
                if (!sessionEpoch.isCurrent(loginSession)) return;
                sessionEpoch.invalidate();
                const activeSession = sessionEpoch.capture();
                $("loginPassword").value = "";
                if (isMultiPage) {
                    await redirectAfterLogin();
                    return;
                }
                showConsole();
                await loadInitialData(activeSession);
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(loginSession)) return;
            state.token = "";
            state.currentUser = null;
            clearServerSession();
            setStatus($("loginStatus"), error.message, "error");
        }
    }

    async function redirectAfterLogin() {
        const returnTo = new URLSearchParams(window.location.search).get("returnTo");
        const allowedPath = Object.values(multiPagePaths).includes(returnTo) ? returnTo : multiPagePaths.overviewPage;
        await loadMultiPage(allowedPath, {replaceHistory: true});
    }

    function pageIdForPath(path) {
        return Object.keys(multiPagePaths).find((pageId) => multiPagePaths[pageId] === path) || "";
    }

    async function loadMultiPage(targetPath, options = {}) {
        const pageId = pageIdForPath(targetPath);
        if (!pageId) throw new Error("目标控制台页面不受支持。");

        const revision = ++pageNavigationRevision;
        const response = await window.fetch(targetPath, {
            headers: {Accept: "text/html"},
            credentials: "same-origin"
        });
        if (!response.ok) {
            throw new Error(`页面加载失败(${response.status})，请稍后重试。`);
        }
        const markup = await response.text();
        if (revision !== pageNavigationRevision) return false;

        const nextDocument = new DOMParser().parseFromString(markup, "text/html");
        if (nextDocument.body.dataset.consoleVersion !== "v2"
                || nextDocument.body.dataset.page !== pageId) {
            throw new Error("页面内容与目标版本不匹配，请刷新后重试。");
        }

        sessionEpoch.invalidate();
        submitStateGuard.invalidateAll();
        toolLoadRevision.invalidate();
        agentDetailRevision.invalidate();
        localExampleLoadRevision.invalidate();
        localExampleInstallRevision.invalidateAll();
        const nextNodes = Array.from(nextDocument.body.children)
            .filter((node) => node.tagName !== "SCRIPT")
            .map((node) => document.importNode(node, true));
        document.body.replaceChildren(...nextNodes);
        document.body.dataset.consoleVersion = "v2";
        document.body.dataset.page = pageId;
        document.title = nextDocument.title;
        currentPage = pageId;

        if (options.updateHistory !== false) {
            const method = options.replaceHistory ? "replaceState" : "pushState";
            window.history[method]({consolePage: pageId}, "", targetPath);
        }
        window.scrollTo({top: 0, left: 0});
        bindPageControls();
        toggleHttpConfigFields();
        await initializeMultiPage();
        return true;
    }

    function reportPageNavigationError(error) {
        const status = $("globalStatus") || $("loginStatus");
        setStatus(status, error.message, "error");
    }

    function loginPath() {
        const currentPath = multiPagePaths[currentPage];
        return currentPath
            ? `/console/v2/login.html?returnTo=${encodeURIComponent(currentPath)}`
            : "/console/v2/login.html";
    }

    async function clearServerSession() {
        try {
            await window.fetch("/api/auth/logout", {method: "POST", credentials: "same-origin"});
        } catch {
            // 本地状态仍会立即清空；网络恢复后，服务端 JWT 也会按自身有效期失效。
        }
    }

    async function logout(message = "已安全退出。", redirectTarget = "") {
        sessionEpoch.invalidate();
        submitStateGuard.invalidateAll();
        toolLoadRevision.invalidate();
        agentDetailRevision.invalidate();
        localExampleLoadRevision.invalidate();
        localExampleInstallRevision.invalidateAll();
        state.token = "";
        state.currentUser = null;
        state.agents = [];
        state.tools = [];
        state.localExamples = [];
        state.selectedAgentId = "";
        state.selectedAgent = null;
        state.selectedToolId = "";
        if (isMultiPage) {
            state.editingToolId = "";
            state.runs = [];
            state.runCursor = "";
            state.selectedRunId = "";
            state.auditEvents = [];
            state.auditCursor = "";
            await clearServerSession();
            window.location.replace(redirectTarget || loginPath());
            return;
        }
        resetToolForm();
        state.runs = [];
        state.runCursor = "";
        state.selectedRunId = "";
        state.auditEvents = [];
        state.auditCursor = "";
        $("loginPassword").value = "";
        $("consoleView").hidden = true;
        $("loginView").hidden = false;
        $("currentUser").textContent = "—";
        renderAgents();
        renderTools();
        renderLocalExamples();
        updateAgentOptions();
        renderRuns();
        renderAudit();
        updateOverview();
        resetSessionViews();
        setStatus($("globalStatus"));
        setStatus($("loginStatus"), message, "neutral");
        await clearServerSession();
    }

    function resetSessionViews() {
        [
            ["loginBtn", "登录控制台"],
            ["createAgentBtn", "创建 Agent"],
            ["createToolBtn", "注册 Tool"],
            ["grantToolBtn", "确认授权"],
            ["debugToolBtn", "执行调试"],
            ["runBtn", "执行运行"]
        ].forEach(([id, text]) => {
            const button = $(id);
            submitStateGuard.invalidate(button);
            button.disabled = false;
            button.textContent = text;
        });
        $("runInput").value = "";
        $("debugInput").value = "{}";
        $("debugConfirmedToolName").value = "";
        renderMessage($("runDetail"), "选择一条运行记录查看详情。");
        renderMessage($("debugResult"), "调试结果将显示在这里。");
        ["agentFormStatus", "toolFormStatus", "grantFormStatus", "runFormStatus", "debugFormStatus", "localExampleStatus"]
            .forEach((id) => setStatus($(id)));
    }

    function showConsole() {
        if (isMultiPage) {
            redirectAfterLogin();
            return;
        }
        $("loginView").hidden = true;
        $("consoleView").hidden = false;
        $("currentUser").textContent = state.currentUser?.displayName
            || state.currentUser?.principalId
            || "当前用户";
        navigate("overviewPage");
    }

    function navigate(pageId) {
        if (isMultiPage) {
            const target = multiPagePaths[pageId] || multiPagePaths.overviewPage;
            loadMultiPage(target).catch(reportPageNavigationError);
            return;
        }
        const info = pageInfo[pageId] || pageInfo.overviewPage;
        document.querySelectorAll(".page-view").forEach((page) => {
            page.hidden = page.id !== pageId;
        });
        document.querySelectorAll("#sidebarNav [data-page]").forEach((button) => {
            const active = button.dataset.page === pageId;
            button.classList.toggle("active", active);
            if (active) button.setAttribute("aria-current", "page");
            else button.removeAttribute("aria-current");
        });
        $("pageTitle").textContent = info[0];
        $("pageSubtitle").textContent = info[1];
        if (pageId === "runsPage" && state.selectedAgentId && !state.runs.length) {
            loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error"));
        }
        if (pageId === "auditPage" && !state.auditEvents.length) {
            loadAudit({append: false}).catch((error) => setStatus($("globalStatus"), error.message, "error"));
        }
    }

    async function loadInitialData(session = sessionEpoch.capture()) {
        setStatus($("globalStatus"), "正在加载当前租户资源…");
        try {
            if (isMultiPage) {
                await loadCurrentPage(session);
                if (!sessionEpoch.isCurrent(session)) return;
                setStatus($("globalStatus"));
                return;
            }
            await Promise.all([loadAgents(session), loadTools(undefined, session), loadLocalExamples(undefined, false, session)]);
            if (!sessionEpoch.isCurrent(session)) return;
            if (state.selectedAgentId) await loadRuns({append: false});
            if (!sessionEpoch.isCurrent(session)) return;
            setStatus($("globalStatus"));
        } catch (error) {
            if (!sessionEpoch.isCurrent(session)) return;
            setStatus($("globalStatus"), error.message, "error");
        }
    }

    async function loadCurrentPage(session) {
        switch (currentPage) {
            case "overviewPage":
                await Promise.all([loadAgents(session), loadTools(undefined, session)]);
                if (sessionEpoch.isCurrent(session) && state.selectedAgentId) {
                    await loadRuns({append: false});
                }
                break;
            case "agentsPage":
                await loadTools(undefined, session);
                if (sessionEpoch.isCurrent(session)) await loadAgents(session);
                break;
            case "toolsPage":
                await Promise.all([
                    loadAgents(session),
                    loadTools(undefined, session),
                    loadLocalExamples(undefined, false, session)
                ]);
                break;
            case "runsPage":
                await loadAgents(session);
                if (sessionEpoch.isCurrent(session)) await loadRuns({append: false});
                break;
            case "auditPage":
                await loadAudit({append: false});
                break;
            default:
                break;
        }
    }

    async function loadAgents(session = sessionEpoch.capture()) {
        const agents = await api.request("/api/agents");
        if (!sessionEpoch.isCurrent(session)) return false;
        state.agents = Array.isArray(agents) ? agents : [];
        if (!state.agents.some((agent) => agent.id === state.selectedAgentId)) {
            state.selectedAgentId = state.agents[0]?.id || "";
        }
        renderAgents();
        updateAgentOptions();
        updateOverview();
        if (state.selectedAgentId && $("agentDetail")) {
            await selectAgent(state.selectedAgentId, undefined, session);
        }
        return true;
    }

    async function selectAgent(
        agentId,
        revision = agentDetailRevision.issue(),
        session = sessionEpoch.capture()
    ) {
        if (!sessionEpoch.isCurrent(session)) return false;
        state.selectedAgentId = agentId;
        renderAgents();
        try {
            const agent = await api.request(`/api/agents/${encodeURIComponent(agentId)}`);
            if (!sessionEpoch.isCurrent(session)
                    || !agentDetailRevision.isCurrent(revision)
                    || state.selectedAgentId !== agentId) {
                return false;
            }
            state.selectedAgent = agent;
            renderAgentDetail(agent);
            return true;
        } catch (error) {
            if (!sessionEpoch.isCurrent(session)
                    || !agentDetailRevision.isCurrent(revision)
                    || state.selectedAgentId !== agentId) {
                return false;
            }
            state.selectedAgent = null;
            renderMessage($("agentDetail"), error.message, true);
            return false;
        }
    }

    function renderAgents() {
        const container = $("agentList");
        if (!container) return;
        container.replaceChildren();
        if (!state.agents.length) {
            container.append(emptyState("暂无 Agent，可在右侧创建。"));
            renderMessage($("agentDetail"), "选择 Agent 查看详情。");
            return;
        }
        state.agents.forEach((agent) => {
            const button = element("button", {className: "resource-item", type: "button"});
            button.classList.toggle("active", agent.id === state.selectedAgentId);
            button.append(element("strong", {text: agent.name || "未命名 Agent"}));
            button.append(element("span", {text: `${agent.modelName || "未配置模型"} · ${agent.enabled ? "已启用" : "已停用"}`}));
            button.addEventListener("click", () => selectAgent(agent.id));
            container.append(button);
        });
    }

    function renderAgentDetail(agent) {
        const container = $("agentDetail");
        if (!container) return;
        const heading = element("div", {className: "panel-heading"});
        const titleGroup = element("div");
        titleGroup.append(element("p", {className: "eyebrow", text: "Agent 详情"}));
        titleGroup.append(element("h2", {text: agent.name || "未命名 Agent"}));
        heading.append(titleGroup);
        const dl = definitionList([
            ["ID", agent.id],
            ["模型", agent.modelName],
            ["状态", agent.enabled ? "已启用" : "已停用"],
            ["温度", agent.temperature],
            ["最大迭代", agent.maxIterations],
            ["工具数量", Array.isArray(agent.toolIds) ? agent.toolIds.length : 0],
            ["System Prompt", agent.systemPrompt]
        ]);
        const toolsSection = element("section", {className: "detail-section"});
        toolsSection.append(element("h3", {text: "已关联工具"}));
        const toolIds = Array.isArray(agent.toolIds) ? agent.toolIds : [];
        if (!toolIds.length) {
            toolsSection.append(emptyState("该 Agent 尚未关联工具。"));
        } else {
            toolIds.forEach((toolId) => {
                const tool = state.tools.find((item) => item.id === toolId);
                const row = element("article", {className: "tool-card"});
                row.append(element("strong", {text: tool?.name || toolId}));
                const actions = element("div", {className: "tool-actions"});
                const revokeButton = element("button", {
                    className: "button ghost",
                    type: "button",
                    text: "解除关联"
                });
                revokeButton.addEventListener("click", () => revokeToolGrant(agent, tool || {id: toolId}, revokeButton));
                actions.append(revokeButton);
                row.append(actions);
                toolsSection.append(row);
            });
        }
        container.replaceChildren(heading, dl, toolsSection);
    }

    async function revokeToolGrant(agent, tool, button) {
        if (!window.confirm(`确认解除 Agent“${agent.name || agent.id}”与 Tool“${tool.name || tool.id}”的关联吗？`)) {
            return;
        }
        const operationSession = sessionEpoch.capture();
        agentDetailRevision.invalidate();
        toolLoadRevision.invalidate();
        try {
            await withSubmitState(button, async () => {
                await api.request(core.buildToolGrantDeletePath(tool.id, agent.id), {method: "DELETE"});
                if (!sessionEpoch.isCurrent(operationSession)) return;
                const toolRevision = toolLoadRevision.completeWrite();
                const reloadRevokedAgent = core.shouldReloadRevokedAgent(state.selectedAgentId, agent.id);
                const [agentReloaded, toolsReloaded] = await Promise.all([
                    reloadRevokedAgent
                        ? selectAgent(agent.id, agentDetailRevision.completeWrite(), operationSession)
                        : Promise.resolve(true),
                    loadTools(toolRevision, operationSession)
                ]);
                if (!agentReloaded || !toolsReloaded || !sessionEpoch.isCurrent(operationSession)) return;
                setStatus($("globalStatus"), `已解除 Agent“${agent.name || agent.id}”与 Tool“${tool.name || tool.id}”的关联。`, "success");
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(operationSession)) return;
            setStatus($("globalStatus"), error.message, "error");
        }
    }

    async function createAgent() {
        const payload = {
            name: $("agentName").value.trim(),
            systemPrompt: $("systemPrompt").value.trim(),
            modelName: $("agentModelName").value.trim()
        };
        if (!payload.name || !payload.systemPrompt || !payload.modelName) {
            setStatus($("agentFormStatus"), "请完整填写 Agent 信息。", "error");
            return;
        }
        try {
            await withSubmitState($("createAgentBtn"), async () => {
                const created = await api.request("/api/agents", {method: "POST", body: JSON.stringify(payload)});
                state.selectedAgentId = created.id || "";
                await loadAgents();
                setStatus($("agentFormStatus"), `Agent“${created.name || payload.name}”已创建。`, "success");
            });
        } catch (error) {
            setStatus($("agentFormStatus"), error.message, "error");
        }
    }

    async function loadTools(revision = toolLoadRevision.issue(), session = sessionEpoch.capture()) {
        const tools = await api.request("/api/tools");
        if (!sessionEpoch.isCurrent(session) || !toolLoadRevision.isCurrent(revision)) {
            return false;
        }
        state.tools = Array.isArray(tools) ? tools : [];
        if (!state.tools.some((tool) => tool.id === state.selectedToolId)) {
            state.selectedToolId = state.tools[0]?.id || "";
        }
        if (state.editingToolId && !state.tools.some((tool) => tool.id === state.editingToolId)) {
            resetToolForm();
        }
        renderTools();
        if (state.selectedAgent?.id === state.selectedAgentId) {
            renderAgentDetail(state.selectedAgent);
        }
        updateToolOptions();
        updateDebugToolOptions();
        updateOverview();
        return true;
    }

    async function loadLocalExamples(
        revision = localExampleLoadRevision.issue(),
        throwOnError = false,
        session = sessionEpoch.capture()
    ) {
        try {
            const examples = await api.request("/api/tools/local-examples");
            if (!sessionEpoch.isCurrent(session) || !localExampleLoadRevision.isCurrent(revision)) {
                return false;
            }
            state.localExamples = Array.isArray(examples) ? examples : [];
            renderLocalExamples();
            return true;
        } catch (error) {
            if (!sessionEpoch.isCurrent(session) || !localExampleLoadRevision.isCurrent(revision)) {
                return false;
            }
            state.localExamples = [];
            $("localExampleSection").hidden = false;
            $("localExampleList").replaceChildren(emptyState("内置 LOCAL 示例目录加载失败。"));
            setStatus($("localExampleStatus"), error.message, "error");
            if (throwOnError) {
                throw error;
            }
            return false;
        }
    }

    function renderLocalExamples() {
        const section = $("localExampleSection");
        const container = $("localExampleList");
        if (!section || !container) return;
        section.hidden = state.localExamples.length === 0;
        container.replaceChildren();
        state.localExamples.forEach((example) => {
            const card = element("article", {className: "local-example-card"});
            card.append(element("strong", {text: example.name}));
            card.append(element("span", {text: example.description}));
            card.append(element("span", {
                className: example.runtimeReady ? "runtime-ready" : "runtime-unavailable",
                text: example.installed
                    ? (example.runtimeReady ? "已安装 · 运行时已就绪" : "已安装 · 未注册执行器")
                    : "未安装"
            }));
            const button = element("button", {
                className: "button",
                type: "button",
                text: example.installed ? "已安装" : "添加示例工具"
            });
            button.disabled = Boolean(example.installed);
            button.addEventListener("click", () => installLocalExample(example, button));
            card.append(button);
            container.append(card);
        });
    }

    async function installLocalExample(example, button) {
        if (!localExampleInstallLock.tryAcquire(example.key)) {
            return;
        }
        let installRevision = localExampleInstallRevision.issue(example.key);
        const installSession = sessionEpoch.capture();
        localExampleLoadRevision.invalidate();
        try {
            await withSubmitState(button, async () => {
                const installed = await api.request(core.buildLocalExampleInstallPath(example.key), {
                    method: "POST"
                });
                if (!sessionEpoch.isCurrent(installSession)) return;
                state.selectedToolId = installed.toolId;
                const reloadRevision = localExampleLoadRevision.completeWrite();
                installRevision = localExampleInstallRevision.completeWrite(example.key);
                await Promise.all([
                    loadLocalExamples(reloadRevision, true, installSession),
                    loadTools(undefined, installSession)
                ]);
                if (!sessionEpoch.isCurrent(installSession)
                        || !localExampleInstallRevision.isCurrent(example.key, installRevision)) {
                    return;
                }
                $("debugToolSelect").value = installed.toolId;
                $("debugInput").value = core.formatJsonInput(installed.sampleInput);
                $("debugToolForm").scrollIntoView({behavior: "smooth", block: "start"});
                $("debugInput").focus();
                setStatus($("localExampleStatus"), `示例“${installed.name}”已安装，可调用调试。`, "success");
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(installSession)) return;
            setStatus($("localExampleStatus"), error.message, "error");
        } finally {
            if (sessionEpoch.isCurrent(installSession)
                    && localExampleInstallRevision.isCurrent(example.key, installRevision)) {
                localExampleInstallRevision.invalidate(example.key);
            }
            localExampleInstallLock.release(example.key);
        }
    }

    function renderTools() {
        const container = $("toolList");
        if (!container) return;
        container.replaceChildren();
        if (!state.tools.length) {
            container.append(emptyState("暂无 Tool，可在右侧注册。"));
            return;
        }
        state.tools.forEach((tool) => {
            const card = element("article", {className: "tool-card"});
            const item = element("button", {className: "resource-item", type: "button"});
            item.classList.toggle("active", tool.id === state.selectedToolId);
            item.append(element("strong", {text: tool.name || "未命名 Tool"}));
            item.append(element("span", {text: `${tool.type || "未知类型"} · ${tool.riskLevel || "未知风险"} · ${tool.enabled ? "已启用" : "已停用"}`}));
            item.append(element("span", {text: `Endpoint：${tool.endpoint || tool.httpConfig?.urlTemplate || "—"}`}));
            item.append(element("span", {text: `MCP：${tool.mcpPublished ? "已发布" : "未发布"}`}));
            if (tool.type === "LOCAL") {
                item.append(element("span", {
                    className: tool.runtimeReady ? "runtime-ready" : "runtime-unavailable",
                    text: tool.runtimeReady ? "运行时已就绪" : "未注册执行器"
                }));
            }
            item.addEventListener("click", () => {
                state.selectedToolId = tool.id;
                renderTools();
                $("grantToolSelect").value = tool.id;
                $("debugToolSelect").value = tool.id;
            });
            card.append(item);
            const actions = element("div", {className: "tool-actions"});
            const editButton = element("button", {
                className: "button ghost",
                type: "button",
                text: "编辑"
            });
            editButton.addEventListener("click", () => beginToolEdit(tool));
            actions.append(editButton);
            if (tool.type === "HTTP" || tool.type === "LOCAL") {
                const publicationButton = element("button", {
                    className: tool.mcpPublished ? "button ghost" : "button",
                    type: "button",
                    text: tool.mcpPublished ? "取消 MCP 发布" : "发布为 MCP Tool"
                });
                publicationButton.addEventListener("click", () => {
                    const action = tool.mcpPublished ? unpublishMcpTool : publishMcpTool;
                    action(tool, publicationButton);
                });
                actions.append(publicationButton);
                if (tool.runtimeReady === true && (core.canDebugTool(tool, "") || tool.riskLevel === "HIGH")) {
                    const debugButton = element("button", {
                        className: "button ghost",
                        type: "button",
                        text: "调用/调试"
                    });
                    debugButton.addEventListener("click", () => {
                        state.selectedToolId = tool.id;
                        $("debugToolSelect").value = tool.id;
                        $("debugToolForm").scrollIntoView({behavior: "smooth", block: "start"});
                        $("debugInput").focus();
                    });
                    actions.append(debugButton);
                }
            }
            const deleteButton = element("button", {
                className: "button ghost",
                type: "button",
                text: "删除"
            });
            deleteButton.addEventListener("click", () => deleteTool(tool, deleteButton));
            actions.append(deleteButton);
            card.append(actions);
            container.append(card);
        });
    }

    function nextParameterId() {
        const randomId = typeof window.crypto?.randomUUID === "function"
            ? window.crypto.randomUUID().replaceAll("-", "")
            : `${Date.now()}${Math.random().toString(16).slice(2)}`;
        return `param_${randomId.slice(0, 24)}`;
    }

    function addLabeledParameterControl(grid, labelText, control, wide = false) {
        const wrapper = element("div", {className: wide ? "parameter-wide" : ""});
        const label = element("label", {text: labelText});
        label.append(control);
        wrapper.append(label);
        grid.append(wrapper);
        return wrapper;
    }

    function addHttpParameter(definition = {}, parentCard = null, refresh = true) {
        const node = element("section", {className: "parameter-tree-node"});
        const card = element("section", {className: "parameter-card"});
        card.dataset.parameterId = definition.id || nextParameterId();
        node.dataset.parentId = parentCard?.dataset.parameterId || "";
        card._parameterMetadata = Object.fromEntries([
            "enumValues", "minLength", "maxLength", "minimum", "maximum",
            "minItems", "maxItems", "uniqueItems"
        ].filter((key) => definition[key] !== undefined && definition[key] !== null)
                .map((key) => [key, definition[key]]));

        const header = element("div", {className: "parameter-card-header"});
        const titleGroup = element("div", {className: "parameter-title-group"});
        const title = element("strong", {text: definition.name || "新参数"});
        const hierarchyBadge = element("span", {className: "parameter-hierarchy-badge", text: "顶层参数"});
        titleGroup.append(title, hierarchyBadge);
        const headerActions = element("div", {className: "parameter-card-actions"});
        const addChildButton = element("button", {className: "text-button parameter-add-child", type: "button", text: "＋ 添加子参数"});
        const removeButton = element("button", {className: "text-button", type: "button", text: "删除"});
        headerActions.append(addChildButton, removeButton);
        header.append(titleGroup, headerActions);

        const grid = element("div", {className: "parameter-grid"});
        const nameInput = element("input");
        nameInput.type = "text";
        nameInput.maxLength = 160;
        nameInput.placeholder = "例如 orderId";
        nameInput.value = definition.name || "";
        nameInput.dataset.parameterField = "name";
        const typeSelect = element("select");
        typeSelect.dataset.parameterField = "dataType";
        ["STRING", "INTEGER", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY"]
            .forEach((value) => typeSelect.append(option(value, value)));
        typeSelect.value = definition.dataType || "STRING";
        typeSelect.dataset.previousValue = typeSelect.value;
        const locationSelect = element("select");
        locationSelect.dataset.parameterField = "requestLocation";
        locationSelect.append(option("", "继承父参数"));
        ["PATH", "QUERY", "HEADER", "BODY", "BODY_ROOT"]
            .forEach((value) => locationSelect.append(option(value, value)));
        locationSelect.value = definition.requestLocation || "";
        const descriptionInput = element("input");
        descriptionInput.type = "text";
        descriptionInput.maxLength = 500;
        descriptionInput.placeholder = "说明该参数的业务含义";
        descriptionInput.value = definition.description || "";
        descriptionInput.dataset.parameterField = "description";
        const defaultInput = element("input");
        defaultInput.type = "text";
        defaultInput.placeholder = "可选，填写 JSON 值，如 20、false 或 \"OPEN\"";
        defaultInput.value = definition.defaultValue === undefined || definition.defaultValue === null
            ? "" : JSON.stringify(definition.defaultValue);
        defaultInput.dataset.parameterField = "defaultValueText";
        const exampleInput = element("input");
        exampleInput.type = "text";
        exampleInput.placeholder = "可选，填写 JSON 示例值";
        exampleInput.value = definition.exampleValue === undefined || definition.exampleValue === null
            ? "" : JSON.stringify(definition.exampleValue);
        exampleInput.dataset.parameterField = "exampleValueText";

        addLabeledParameterControl(grid, "字段名称", nameInput);
        addLabeledParameterControl(grid, "数据类型", typeSelect);
        const locationWrapper = addLabeledParameterControl(grid, "请求位置", locationSelect);
        locationWrapper.classList.add("parameter-location-field");
        addLabeledParameterControl(grid, "参数说明", descriptionInput, true);
        addLabeledParameterControl(grid, "默认值", defaultInput);
        addLabeledParameterControl(grid, "示例值", exampleInput);

        const requiredLabel = element("label", {className: "parameter-required"});
        const requiredInput = element("input");
        requiredInput.type = "checkbox";
        requiredInput.checked = definition.required === true;
        requiredInput.dataset.parameterField = "required";
        requiredLabel.append(requiredInput, document.createTextNode("调用时必填"));
        const idText = element("p", {className: "parameter-id", text: `ID：${card.dataset.parameterId}`});
        card.append(header, grid, requiredLabel, idText);
        const children = element("div", {className: "parameter-children"});
        node.append(card, children);
        if (parentCard) {
            parameterChildrenContainer(parentCard).append(node);
        } else {
            $("httpParameterList").append(node);
        }

        removeButton.addEventListener("click", () => removeHttpParameter(card));
        addChildButton.addEventListener("click", () => addHttpParameterChild(card));
        typeSelect.addEventListener("change", () => {
            if (parameterChildNodes(card).length && typeSelect.value !== typeSelect.dataset.previousValue) {
                typeSelect.value = typeSelect.dataset.previousValue;
                setStatus($("toolFormStatus"), "该参数已有子参数，请先删除子参数再修改数据类型。", "error");
                return;
            }
            typeSelect.dataset.previousValue = typeSelect.value;
            syncHttpParameterCards();
        });
        nameInput.addEventListener("input", () => {
            title.textContent = nameInput.value.trim() || (nameInput.disabled ? "数组元素" : "新参数");
        });
        if (refresh) {
            syncHttpParameterCards();
            updateHttpParameterCount();
        }
        return card;
    }

    function addHttpParameterChild(parentCard) {
        const parentType = parameterControl(parentCard, "dataType").value;
        if (parentType !== "OBJECT" && parentType !== "ARRAY") return;
        if (parentType === "ARRAY" && parameterChildNodes(parentCard).length) return;
        addHttpParameter({
            parentId: parentCard.dataset.parameterId,
            name: parentType === "ARRAY" ? "" : "",
            dataType: "STRING",
            requestLocation: null,
            required: false
        }, parentCard);
    }

    function renderHttpParameterTree(definitions) {
        clearHttpParameters();
        const parameters = Array.isArray(definitions) ? definitions : [];
        const childrenByParent = new Map();
        for (const parameter of parameters) {
            const parentId = parameter.parentId || "";
            if (!childrenByParent.has(parentId)) childrenByParent.set(parentId, []);
            childrenByParent.get(parentId).push(parameter);
        }
        const visited = new Set();
        function appendBranch(definition, parentCard) {
            if (visited.has(definition.id)) return;
            visited.add(definition.id);
            const card = addHttpParameter(definition, parentCard, false);
            (childrenByParent.get(definition.id) || []).forEach((child) => appendBranch(child, card));
        }
        (childrenByParent.get("") || []).forEach((root) => appendBranch(root, null));
        syncHttpParameterCards();
        updateHttpParameterCount();
    }

    function parameterCards() {
        return Array.from($("httpParameterList").querySelectorAll(".parameter-card"));
    }

    function parameterControl(card, field) {
        return card.querySelector(`[data-parameter-field="${field}"]`);
    }

    function parameterNode(card) {
        return card.closest(".parameter-tree-node");
    }

    function parameterChildrenContainer(card) {
        return Array.from(parameterNode(card).children)
            .find((child) => child.classList.contains("parameter-children"));
    }

    function parameterChildNodes(card) {
        return Array.from(parameterChildrenContainer(card).children)
            .filter((child) => child.classList.contains("parameter-tree-node"));
    }

    function parameterDepth(card) {
        let depth = 0;
        let node = parameterNode(card);
        while (node?.dataset.parentId) {
            depth += 1;
            node = node.parentElement?.closest(".parameter-tree-node");
        }
        return depth;
    }

    function syncHttpParameterCards() {
        const cards = parameterCards();
        const byId = new Map(cards.map((card) => [card.dataset.parameterId, card]));
        for (const card of cards) {
            const parentId = parameterNode(card).dataset.parentId;
            const parent = byId.get(parentId);
            const name = parameterControl(card, "name");
            const location = parameterControl(card, "requestLocation");
            const required = parameterControl(card, "required");
            const defaultValue = parameterControl(card, "defaultValueText");
            const type = parameterControl(card, "dataType").value;
            const addChildButton = card.querySelector(".parameter-add-child");
            const locationField = card.querySelector(".parameter-location-field");
            if (!parent) {
                name.disabled = false;
                locationField.hidden = false;
                location.disabled = false;
                if (!location.value) location.value = "QUERY";
                required.disabled = false;
            } else {
                location.value = "";
                location.disabled = true;
                locationField.hidden = true;
                if (parameterControl(parent, "dataType").value === "ARRAY") {
                    name.value = "";
                    name.disabled = true;
                    required.checked = false;
                    required.disabled = true;
                    defaultValue.value = "";
                    defaultValue.disabled = true;
                } else {
                    name.disabled = false;
                    required.disabled = false;
                    defaultValue.disabled = false;
                }
            }
            if (!parent) defaultValue.disabled = false;
            const childCount = parameterChildNodes(card).length;
            addChildButton.hidden = type !== "OBJECT" && type !== "ARRAY";
            addChildButton.disabled = type === "ARRAY" && childCount > 0;
            addChildButton.textContent = type === "ARRAY"
                ? childCount ? "已有数组元素" : "＋ 添加数组元素"
                : "＋ 添加子参数";
            const depth = parameterDepth(card);
            parameterNode(card).style.setProperty("--parameter-depth", String(depth));
            card.querySelector(".parameter-hierarchy-badge").textContent = !parent
                ? "顶层参数"
                : parameterControl(parent, "dataType").value === "ARRAY" ? "数组元素" : `第 ${depth + 1} 层`;
            card.querySelector(".parameter-card-header strong").textContent = name.value.trim()
                || (name.disabled ? "数组元素" : "新参数");
        }
    }

    function removeHttpParameter(card) {
        const node = parameterNode(card);
        const descendants = node.querySelectorAll(".parameter-tree-node").length;
        if (descendants && !window.confirm("删除父参数会同时删除全部子参数，是否继续？")) return;
        node.remove();
        syncHttpParameterCards();
        updateHttpParameterCount();
    }

    function clearHttpParameters() {
        $("httpParameterList").querySelectorAll(".parameter-tree-node").forEach((node) => node.remove());
        updateHttpParameterCount();
    }

    function updateHttpParameterCount() {
        const count = parameterCards().length;
        $("httpParameterCount").textContent = `${count} 个参数`;
        $("httpParameterEmpty").hidden = count > 0;
    }

    function collectHttpParameters() {
        const parameters = [];
        function visit(node, parentId) {
            const card = Array.from(node.children)
                .find((child) => child.classList.contains("parameter-card"));
            const value = {...card._parameterMetadata};
            ["name", "dataType", "requestLocation", "description", "defaultValueText", "exampleValueText"]
                .forEach((field) => { value[field] = parameterControl(card, field).value; });
            value.id = card.dataset.parameterId;
            value.parentId = parentId;
            value.required = parameterControl(card, "required").checked;
            parameters.push(value);
            parameterChildNodes(card).forEach((child) => visit(child, value.id));
        }
        Array.from($("httpParameterList").children)
            .filter((child) => child.classList.contains("parameter-tree-node"))
            .forEach((root) => visit(root, ""));
        return parameters;
    }

    function beginToolEdit(tool) {
        state.editingToolId = tool.id;
        state.selectedToolId = tool.id;
        $("toolName").value = tool.name || "";
        $("toolDescription").value = tool.description || "";
        $("toolType").value = tool.type || "LOCAL";
        $("toolRiskLevel").value = tool.riskLevel || "LOW";
        $("toolEnabled").checked = tool.enabled !== false;
        $("toolMcpPublished").checked = tool.mcpPublished === true;
        $("toolType").disabled = true;
        $("toolName").disabled = tool.type === "LOCAL";
        if (tool.type === "HTTP") {
            const config = tool.httpConfig || {};
            $("httpMethod").value = config.method || "GET";
            $("httpUrlTemplate").value = config.urlTemplate || "";
            $("httpSecretHeaders").value = formatStoredJson(config.secretHeaders, "{}");
            $("httpTimeoutMillis").value = String(config.timeoutMillis || 1000);
            clearHttpParameters();
            renderHttpParameterTree(config.parameters);
        }
        $("toolFormEyebrow").textContent = "编辑";
        $("toolFormTitle").textContent = `编辑 Tool“${tool.name || tool.id}”`;
        $("createToolBtn").textContent = "保存修改";
        $("cancelToolEditBtn").hidden = false;
        toggleHttpConfigFields();
        renderTools();
        setStatus($("toolFormStatus"), "类型不可修改；LOCAL Tool 名称也保持锁定。");
        $("toolForm").scrollIntoView({behavior: "smooth", block: "start"});
        $("toolDescription").focus();
    }

    function resetToolForm(clearStatus = true) {
        state.editingToolId = "";
        $("toolForm").reset();
        clearHttpParameters();
        $("httpParameterEditor").hidden = false;
        $("toolType").disabled = false;
        $("toolName").disabled = false;
        $("toolFormEyebrow").textContent = "新建";
        $("toolFormTitle").textContent = "注册 Tool";
        $("createToolBtn").textContent = "注册 Tool";
        $("cancelToolEditBtn").hidden = true;
        toggleHttpConfigFields();
        if (clearStatus) setStatus($("toolFormStatus"));
    }

    function fillHttpToolExample() {
        const hasUserConfig = $("httpUrlTemplate").value.trim()
            || parameterCards().length > 0
            || $("httpSecretHeaders").value.trim() !== "{}"
            || $("httpTimeoutMillis").value !== "1000";
        if (hasUserConfig && !window.confirm("填入示例会覆盖当前 HTTP 配置，是否继续？")) return;

        $("httpMethod").value = HTTP_TOOL_FORM_EXAMPLE.method;
        $("httpUrlTemplate").value = HTTP_TOOL_FORM_EXAMPLE.urlTemplate;
        $("httpParameterEditor").hidden = false;
        renderHttpParameterTree(HTTP_TOOL_FORM_EXAMPLE.parameters);
        $("httpSecretHeaders").value = core.formatJsonInput(HTTP_TOOL_FORM_EXAMPLE.secretHeaders);
        $("httpTimeoutMillis").value = String(HTTP_TOOL_FORM_EXAMPLE.timeoutMillis);
        setStatus(
            $("toolFormStatus"),
            "示例已填入表单，提交前请替换为已加入白名单的业务域名和已配置的 Secret 引用。"
        );
        $("httpUrlTemplate").focus();
    }

    function formatStoredJson(value, fallback) {
        if (value === null || value === undefined || value === "") return fallback;
        if (typeof value !== "string") return core.formatJsonInput(value);
        try {
            return core.formatJsonInput(JSON.parse(value));
        } catch {
            return value;
        }
    }

    async function submitTool() {
        const rawFormFields = {
            name: $("toolName").value.trim(),
            description: $("toolDescription").value.trim(),
            type: $("toolType").value,
            riskLevel: $("toolRiskLevel").value,
            enabled: $("toolEnabled").checked,
            mcpPublished: $("toolMcpPublished").checked,
            method: $("httpMethod").value,
            urlTemplate: $("httpUrlTemplate").value,
            parameters: collectHttpParameters(),
            secretHeadersText: $("httpSecretHeaders").value,
            timeoutMillis: $("httpTimeoutMillis").value
        };
        const editingTool = state.tools.find((tool) => tool.id === state.editingToolId);
        if (state.editingToolId && !editingTool) {
            setStatus($("toolFormStatus"), "待编辑 Tool 已不可用，请刷新后重试。", "error");
            return;
        }
        let payload;
        try {
            payload = core.buildToolFormPayload(editingTool, rawFormFields);
        } catch (error) {
            setStatus($("toolFormStatus"), error.message, "error");
            return;
        }
        if (!payload.name || !payload.description) {
            setStatus($("toolFormStatus"), "请完整填写 Tool 信息。", "error");
            return;
        }
        const operationSession = sessionEpoch.capture();
        toolLoadRevision.invalidate();
        try {
            await withSubmitState($("createToolBtn"), async () => {
                const saved = await api.request(
                    editingTool ? core.buildToolUpdatePath(editingTool.id) : "/api/tools",
                    {method: editingTool ? "PUT" : "POST", body: JSON.stringify(payload)}
                );
                if (!sessionEpoch.isCurrent(operationSession)) return;
                const resetSavedEdit = !editingTool
                    || core.shouldResetSavedToolForm(state.editingToolId, editingTool.id);
                if (resetSavedEdit) {
                    state.selectedToolId = saved.id || editingTool?.id || "";
                }
                const successText = editingTool
                    ? `Tool“${saved.name || payload.name}”已更新。`
                    : `Tool“${saved.name || payload.name}”已注册。`;
                if (resetSavedEdit) resetToolForm(false);
                const reloadRevision = toolLoadRevision.completeWrite();
                const toolsReloaded = await loadTools(reloadRevision, operationSession);
                if (!toolsReloaded || !sessionEpoch.isCurrent(operationSession)) return;
                setStatus($("toolFormStatus"), successText, "success");
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(operationSession)) return;
            setStatus($("toolFormStatus"), error.message, "error");
        }
    }

    async function deleteTool(tool, button) {
        if (!window.confirm(`确认删除 Tool“${tool.name || tool.id}”吗？此操作不可撤销。`)) {
            return;
        }
        const operationSession = sessionEpoch.capture();
        toolLoadRevision.invalidate();
        try {
            await withSubmitState(button, async () => {
                await api.request(core.buildToolDeletePath(tool.id), {method: "DELETE"});
                if (!sessionEpoch.isCurrent(operationSession)) return;
                if (state.editingToolId === tool.id) resetToolForm();
                const reloadRevision = toolLoadRevision.completeWrite();
                const toolsReloaded = await loadTools(reloadRevision, operationSession);
                if (!toolsReloaded || !sessionEpoch.isCurrent(operationSession)) return;
                setStatus($("globalStatus"), `Tool“${tool.name || tool.id}”已删除。`, "success");
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(operationSession)) return;
            const message = core.isToolDeleteConflict(error)
                ? `Tool“${tool.name || tool.id}”仍被 Agent 使用，请先到 Agent 详情解除关联。`
                : error.message;
            setStatus($("globalStatus"), message, "error");
        }
    }

    async function grantTool() {
        const toolId = $("grantToolSelect").value;
        const agentId = $("grantAgentSelect").value;
        if (!toolId || !agentId) {
            setStatus($("grantFormStatus"), "请选择 Tool 和 Agent。", "error");
            return;
        }
        const operationSession = sessionEpoch.capture();
        agentDetailRevision.invalidate();
        try {
            await withSubmitState($("grantToolBtn"), async () => {
                await api.request(`/api/tools/${encodeURIComponent(toolId)}/grants`, {
                    method: "POST",
                    body: JSON.stringify({agentId})
                });
                if (!sessionEpoch.isCurrent(operationSession)) return;
                setStatus($("grantFormStatus"), "授权已生效。", "success");
                if (agentId === state.selectedAgentId) {
                    await selectAgent(agentId, agentDetailRevision.completeWrite(), operationSession);
                }
            });
        } catch (error) {
            if (!sessionEpoch.isCurrent(operationSession)) return;
            setStatus($("grantFormStatus"), error.message, "error");
        }
    }

    function updateAgentOptions() {
        [$("grantAgentSelect"), $("runAgentSelect")].filter(Boolean).forEach((select) => {
            select.replaceChildren();
            if (!state.agents.length) {
                select.append(option("", "暂无 Agent"));
                select.disabled = true;
                return;
            }
            select.disabled = false;
            state.agents.forEach((agent) => select.append(option(agent.id, agent.name || agent.id)));
            select.value = state.selectedAgentId || state.agents[0].id;
        });
    }

    function updateToolOptions() {
        const select = $("grantToolSelect");
        if (!select) return;
        select.replaceChildren();
        if (!state.tools.length) {
            select.append(option("", "暂无 Tool"));
            select.disabled = true;
            return;
        }
        select.disabled = false;
        state.tools.forEach((tool) => select.append(option(tool.id, tool.name || tool.id)));
        select.value = state.selectedToolId || state.tools[0].id;
    }

    function updateDebugToolOptions() {
        const select = $("debugToolSelect");
        if (!select) return;
        const debugTools = state.tools.filter((tool) => tool.runtimeReady === true
            && (tool.type === "HTTP" || tool.type === "LOCAL"));
        select.replaceChildren();
        if (!debugTools.length) {
            select.append(option("", "暂无可调试 Tool"));
            select.disabled = true;
            return;
        }
        select.disabled = false;
        debugTools.forEach((tool) => select.append(option(tool.id, `${tool.name || tool.id} · ${tool.type}`)));
        select.value = debugTools.some((tool) => tool.id === state.selectedToolId)
            ? state.selectedToolId : debugTools[0].id;
    }

    function toggleHttpConfigFields() {
        if (!$("toolType")) return;
        const isHttp = $("toolType").value === "HTTP";
        const supportsMcp = isHttp || (state.editingToolId && $("toolType").value === "LOCAL");
        $("httpConfigFields").hidden = !isHttp;
        $("toolEnabledField").hidden = !state.editingToolId;
        $("mcpPublicationField").hidden = !supportsMcp;
        $("toolMcpPublished").disabled = !supportsMcp;
        if (!supportsMcp) $("toolMcpPublished").checked = false;
        ["httpUrlTemplate", "httpSecretHeaders", "httpTimeoutMillis"].forEach((id) => {
            $(id).required = isHttp;
        });
    }

    function publishMcpTool(tool, publicationButton) {
        return changeMcpPublication(tool, publicationButton, "PUT", "已发布为 MCP Tool。");
    }

    function unpublishMcpTool(tool, publicationButton) {
        return changeMcpPublication(tool, publicationButton, "DELETE", "已取消 MCP 发布。");
    }

    async function changeMcpPublication(tool, publicationButton, method, successText) {
        if (!toolPublicationLock.tryAcquire(tool.id)) {
            return;
        }
        const originalText = publicationButton.textContent;
        publicationButton.disabled = true;
        publicationButton.textContent = "处理中…";
        toolLoadRevision.invalidate();
        let operationError = null;
        try {
            await api.request(`/api/tools/${encodeURIComponent(tool.id)}/mcp-publication`, {method});
        } catch (error) {
            operationError = error;
            setStatus($("globalStatus"), error.message, "error");
        } finally {
            try {
                const reloadRevision = toolLoadRevision.completeWrite();
                await loadTools(reloadRevision);
                if (!operationError) {
                    setStatus($("globalStatus"), `Tool“${tool.name || tool.id}”${successText}`, "success");
                }
            } catch (reloadError) {
                setStatus($("globalStatus"), reloadError.message, "error");
            } finally {
                toolPublicationLock.release(tool.id);
                publicationButton.disabled = false;
                publicationButton.textContent = originalText;
            }
        }
    }

    async function debugTool() {
        const tool = state.tools.find((item) => item.id === $("debugToolSelect").value);
        const confirmedToolName = $("debugConfirmedToolName").value;
        if (!tool) {
            setStatus($("debugFormStatus"), "请选择可调试的 Tool。", "error");
            return;
        }
        if (!core.canDebugTool(tool, confirmedToolName)) {
            const message = tool.runtimeReady !== true
                ? "该 Tool 尚未就绪，未注册执行器或运行配置不可用。"
                : tool.riskLevel === "HIGH"
                    ? "HIGH 风险 Tool 的确认名称必须与 Tool 名称完全一致。"
                    : "该 Tool 类型不支持调试。";
            setStatus($("debugFormStatus"), message, "error");
            return;
        }
        let input;
        try {
            input = core.parseJsonField($("debugInput").value, "调试输入");
        } catch (error) {
            setStatus($("debugFormStatus"), error.message, "error");
            return;
        }
        try {
            await withSubmitState($("debugToolBtn"), async () => {
                const result = await api.request(`/api/tools/${encodeURIComponent(tool.id)}/debug`, {
                    method: "POST",
                    body: JSON.stringify({input, confirmedToolName: tool.riskLevel === "HIGH" ? confirmedToolName : null})
                });
                renderDebugResult(result);
                setStatus(
                    $("debugFormStatus"),
                    result?.success ? "调试完成。" : core.formatToolDebugFailure(result),
                    result?.success ? "success" : "error"
                );
            });
        } catch (error) {
            setStatus($("debugFormStatus"), error.message, "error");
        }
    }

    function renderDebugResult(result) {
        const container = $("debugResult");
        const statusText = result?.success ? "成功" : "失败";
        container.replaceChildren(definitionList([
            ["状态", statusText],
            ["HTTP 状态", result?.statusCode],
            ["耗时", result?.durationMillis === null || result?.durationMillis === undefined ? "—" : `${result.durationMillis} ms`],
            ["输出", result?.output],
            ["错误原因", result?.errorMessage],
            ["错误码", result?.errorCode],
            ["错误编号", result?.errorId]
        ]));
    }

    function updateOverview() {
        if (!$("overviewAgentCount")) return;
        $("overviewAgentCount").textContent = String(state.agents.length);
        $("overviewToolCount").textContent = String(state.tools.length);
        const latestRun = state.runs[0];
        const meta = core.statusMeta(latestRun?.status);
        $("overviewRunStatus").textContent = latestRun ? meta.label : "暂无";
        $("overviewRunTime").textContent = latestRun ? core.formatDateTime(latestRun.startedAt) : (state.agents.length ? "暂无运行记录" : "尚无 Agent");
        const container = $("overviewRuns");
        container.replaceChildren();
        if (!state.runs.length) {
            container.append(emptyState(state.agents.length ? "当前 Agent 暂无运行记录。" : "选择或创建 Agent 后查看运行记录。"));
            return;
        }
        state.runs.slice(0, 5).forEach((run) => {
            const row = element("button", {className: "overview-run", type: "button"});
            const agent = state.agents.find((item) => item.id === run.agentId);
            row.append(element("strong", {text: agent?.name || "Agent"}));
            row.append(statusBadge(run.status));
            row.append(element("span", {text: core.formatDateTime(run.startedAt)}));
            row.addEventListener("click", () => {
                navigate("runsPage");
                if (isMultiPage) return;
                loadRunDetail(run.id);
            });
            container.append(row);
        });
    }

    async function runAgent() {
        const agentId = $("runAgentSelect").value;
        const input = $("runInput").value.trim();
        if (!agentId || !input) {
            setStatus($("runFormStatus"), "请选择 Agent 并输入调试内容。", "error");
            return;
        }
        try {
            await withSubmitState($("runBtn"), async () => {
                const result = await api.request(`/api/agents/${encodeURIComponent(agentId)}/runs`, {
                    method: "POST",
                    body: JSON.stringify({input})
                });
                state.selectedAgentId = agentId;
                await loadRuns({append: false});
                if (result?.runId) await loadRunDetail(result.runId);
                const meta = core.statusMeta(result?.status);
                setStatus($("runFormStatus"), `运行已结束：${meta.label}。`, result?.status === "SUCCEEDED" ? "success" : "error");
            });
        } catch (error) {
            setStatus($("runFormStatus"), error.message, "error");
        }
    }

    async function loadRuns({append = false} = {}) {
        const agentId = $("runAgentSelect")?.value || state.selectedAgentId;
        if (!agentId) {
            state.runs = [];
            state.runCursor = "";
            renderRuns();
            updateOverview();
            return;
        }
        if (!append) {
            state.selectedAgentId = agentId;
            state.selectedRunId = "";
            if ($("runDetail")) renderMessage($("runDetail"), "选择一条运行记录查看详情。");
        }
        const basePath = `/api/agents/${encodeURIComponent(agentId)}/runs`;
        const path = core.buildCursorPath(basePath, 20, append ? state.runCursor : "");
        const page = await api.request(path);
        const merged = core.appendCursorPage(append ? state.runs : [], page);
        state.runs = merged.items;
        state.runCursor = merged.nextCursor;
        renderRuns();
        updateOverview();
    }

    function renderRuns() {
        const container = $("runList");
        if (!container) return;
        container.replaceChildren();
        if (!state.runs.length) {
            container.append(emptyState(state.selectedAgentId ? "当前 Agent 暂无运行记录。" : "请选择 Agent。"));
        } else {
            state.runs.forEach((run) => {
                const item = element("button", {className: "resource-item", type: "button"});
                item.classList.toggle("active", run.id === state.selectedRunId);
                const heading = element("span", {className: "resource-heading"});
                heading.append(statusBadge(run.status));
                heading.append(element("strong", {text: core.formatDateTime(run.startedAt)}));
                item.append(heading);
                item.append(element("span", {text: compactText(run.input, 90) || "无输入"}));
                item.addEventListener("click", () => loadRunDetail(run.id));
                container.append(item);
            });
        }
        $("loadMoreRunsBtn").hidden = !state.runCursor;
        $("runsEndStatus").textContent = state.runs.length && !state.runCursor ? "已加载全部" : "";
    }

    async function loadRunDetail(runId) {
        const agentId = $("runAgentSelect")?.value || state.selectedAgentId;
        if (!agentId || !runId) return;
        state.selectedRunId = runId;
        renderRuns();
        try {
            const detail = await api.request(`/api/agents/${encodeURIComponent(agentId)}/runs/${encodeURIComponent(runId)}`);
            renderRunDetail(detail);
        } catch (error) {
            renderMessage($("runDetail"), error.message, true);
        }
    }

    function renderRunDetail(detail) {
        const run = detail?.run || {};
        const container = $("runDetail");
        const heading = element("div", {className: "panel-heading"});
        const titleGroup = element("div");
        titleGroup.append(element("p", {className: "eyebrow", text: "运行详情"}));
        titleGroup.append(element("h2", {text: `运行 ${run.id || "—"}`}));
        heading.append(titleGroup, statusBadge(run.status));
        const dl = definitionList([
            ["输入", run.input],
            ["输出", run.output],
            ["错误", run.errorMessage],
            ["执行主体", run.principalId],
            ["开始时间", core.formatDateTime(run.startedAt)],
            ["结束时间", core.formatDateTime(run.finishedAt)]
        ]);
        const callsSection = element("section", {className: "detail-section"});
        callsSection.id = "runToolCalls";
        callsSection.append(element("h3", {text: "工具调用"}));
        const toolCalls = Array.isArray(detail?.toolCalls) ? detail.toolCalls : [];
        if (!toolCalls.length) {
            callsSection.append(emptyState("本次运行未产生工具调用。"));
        } else {
            toolCalls.forEach((call) => callsSection.append(renderToolCall(call)));
        }
        container.replaceChildren(heading, dl, callsSection);
    }

    function renderToolCall(call) {
        const article = element("article", {className: "tool-call"});
        const heading = element("div", {className: "resource-heading"});
        heading.append(element("strong", {text: call.toolName || "未命名 Tool"}), statusBadge(call.status));
        article.append(heading);
        article.append(definitionList([
            ["已授权", call.authorized ? "是" : "否"],
            ["耗时", call.durationMillis === null || call.durationMillis === undefined ? "—" : `${call.durationMillis} ms`],
            ["输入摘要", call.inputSummary],
            ["输出摘要", call.outputSummary],
            ["错误", call.errorMessage]
        ]));
        return article;
    }

    async function loadAudit({append = false} = {}) {
        const path = core.buildCursorPath("/api/audit-events", 20, append ? state.auditCursor : "");
        const page = await api.request(path);
        const merged = core.appendCursorPage(append ? state.auditEvents : [], page);
        state.auditEvents = merged.items;
        state.auditCursor = merged.nextCursor;
        renderAudit();
    }

    function renderAudit() {
        const container = $("auditList");
        if (!container) return;
        container.replaceChildren();
        state.auditEvents.forEach((event) => {
            const row = document.createElement("tr");
            row.append(tableCell(event.eventType));
            row.append(tableCell(`${event.resourceType || "—"} / ${event.resourceId || "—"}`));
            const statusCell = document.createElement("td");
            statusCell.append(statusBadge(event.status));
            row.append(statusCell);
            row.append(tableCell(event.principalId));
            row.append(tableCell(event.message));
            row.append(tableCell(core.formatDateTime(event.createdAt)));
            container.append(row);
        });
        $("auditEmpty").hidden = state.auditEvents.length > 0;
        $("loadMoreAuditBtn").hidden = !state.auditCursor;
        $("auditEndStatus").textContent = state.auditEvents.length && !state.auditCursor ? "已加载全部" : "";
    }

    function statusBadge(status) {
        const meta = core.statusMeta(status);
        const badge = element("span", {className: "status-badge", text: meta.label});
        badge.dataset.tone = meta.tone;
        return badge;
    }

    function tableCell(value) {
        return element("td", {text: value === null || value === undefined || value === "" ? "—" : value});
    }

    function compactText(value, limit) {
        const text = value ? String(value).trim() : "";
        return text.length > limit ? `${text.slice(0, limit)}…` : text;
    }

    function option(value, label) {
        const node = document.createElement("option");
        node.value = value;
        node.textContent = label;
        return node;
    }

    function emptyState(message) {
        return element("p", {className: "empty-state", text: message});
    }

    function renderMessage(container, message, error = false) {
        const node = emptyState(message);
        if (error) node.dataset.tone = "error";
        container.replaceChildren(node);
    }

    function definitionList(entries) {
        const dl = document.createElement("dl");
        entries.forEach(([label, value]) => {
            dl.append(element("dt", {text: label}));
            dl.append(element("dd", {text: value === null || value === undefined || value === "" ? "—" : value}));
        });
        return dl;
    }

    function bind(id, eventName, listener) {
        const target = $(id);
        if (target) target.addEventListener(eventName, listener);
    }

    function bindPageControls() {
        bind("loginForm", "submit", (event) => { event.preventDefault(); login(); });
        bind("logoutBtn", "click", () => logout());
        bind("agentForm", "submit", (event) => { event.preventDefault(); createAgent(); });
        bind("toolForm", "submit", (event) => { event.preventDefault(); submitTool(); });
        bind("cancelToolEditBtn", "click", () => resetToolForm());
        bind("fillHttpExampleBtn", "click", fillHttpToolExample);
        bind("addHttpParameterBtn", "click", () => addHttpParameter());
        bind("grantForm", "submit", (event) => { event.preventDefault(); grantTool(); });
        bind("debugToolForm", "submit", (event) => { event.preventDefault(); debugTool(); });
        bind("runForm", "submit", (event) => { event.preventDefault(); runAgent(); });
        bind("toolType", "change", toggleHttpConfigFields);
        bind("refreshAgentsBtn", "click", () => loadAgents().catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("refreshToolsBtn", "click", () => loadTools().catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("refreshRunsBtn", "click", () => loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("loadMoreRunsBtn", "click", () => loadRuns({append: true}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("runAgentSelect", "change", () => loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("refreshAuditBtn", "click", () => loadAudit({append: false}).catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("loadMoreAuditBtn", "click", () => loadAudit({append: true}).catch((error) => setStatus($("globalStatus"), error.message, "error")));
        document.querySelectorAll("button[data-page]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.page)));
        document.querySelectorAll("button[data-navigate]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.navigate)));
    }

    function handleMultiPageLink(event) {
        if (!isMultiPage || event.defaultPrevented || event.button !== 0
                || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
        }
        const link = event.target.closest("a[href]");
        if (!link || link.target || link.hasAttribute("download")) return;

        const target = new URL(link.href, window.location.href);
        if (target.origin !== window.location.origin) return;
        if (target.pathname === "/console/v1/") {
            event.preventDefault();
            logout("已切换到旧版控制台。", "/console/v1/");
            return;
        }
        if (!pageIdForPath(target.pathname)) return;
        event.preventDefault();
        loadMultiPage(target.pathname).catch(reportPageNavigationError);
    }

    function handleMultiPageHistory() {
        if (!isMultiPage) return;
        const targetPath = window.location.pathname;
        if (!pageIdForPath(targetPath)) {
            window.location.reload();
            return;
        }
        loadMultiPage(targetPath, {updateHistory: false})
            .catch(() => window.location.reload());
    }

    async function initializeMultiPage() {
        if (currentPage === "login") {
            return;
        }
        try {
            if (!state.currentUser) {
                state.currentUser = await api.request("/api/auth/me");
            }
            $("currentUser").textContent = state.currentUser?.displayName
                || state.currentUser?.principalId
                || "当前用户";
            await loadInitialData();
        } catch (error) {
            if (state.token) setStatus($("globalStatus"), error.message, "error");
        }
    }

    if (isMultiPage) {
        // 监听器挂在不会被替换的 document 上，使各独立 HTML 切换后仍能复用当前内存会话。
        document.addEventListener("click", handleMultiPageLink);
        window.addEventListener("popstate", handleMultiPageHistory);
    }
    bindPageControls();
    toggleHttpConfigFields();
    if (isMultiPage) {
        initializeMultiPage();
    } else {
        logout("请输入用户名和密码。");
    }
})();
