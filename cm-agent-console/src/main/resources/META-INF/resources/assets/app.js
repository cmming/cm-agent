(function () {
    "use strict";

    const core = window.CmAgentConsoleCore;
    if (!core) {
        throw new Error("控制台核心脚本未加载");
    }

    const state = {
        token: "",
        currentUser: null,
        agents: [],
        tools: [],
        selectedAgentId: "",
        selectedToolId: ""
    };

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
        onUnauthorized: () => logout("登录状态已失效，请重新登录。")
    });

    function setStatus(element, message = "", tone = "neutral") {
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
        button.disabled = true;
        button.textContent = "处理中…";
        try {
            return await action();
        } finally {
            button.disabled = false;
            button.textContent = originalText;
        }
    }

    async function login() {
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
                state.token = result?.accessToken || "";
                if (!state.token) throw new Error("登录响应未包含访问令牌。");
                state.currentUser = await api.request("/api/auth/me");
                $("loginPassword").value = "";
                showConsole();
                await loadInitialData();
            });
        } catch (error) {
            state.token = "";
            state.currentUser = null;
            setStatus($("loginStatus"), error.message, "error");
        }
    }

    function logout(message = "已安全退出。") {
        state.token = "";
        state.currentUser = null;
        state.agents = [];
        state.tools = [];
        state.selectedAgentId = "";
        state.selectedToolId = "";
        $("loginPassword").value = "";
        $("consoleView").hidden = true;
        $("loginView").hidden = false;
        $("currentUser").textContent = "—";
        renderAgents();
        renderTools();
        updateAgentOptions();
        updateOverview();
        setStatus($("globalStatus"));
        setStatus($("loginStatus"), message, "neutral");
    }

    function showConsole() {
        $("loginView").hidden = true;
        $("consoleView").hidden = false;
        $("currentUser").textContent = state.currentUser?.displayName
            || state.currentUser?.principalId
            || "当前用户";
        navigate("overviewPage");
    }

    function navigate(pageId) {
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
    }

    async function loadInitialData() {
        setStatus($("globalStatus"), "正在加载当前租户资源…");
        try {
            await Promise.all([loadAgents(), loadTools()]);
            setStatus($("globalStatus"));
        } catch (error) {
            setStatus($("globalStatus"), error.message, "error");
        }
    }

    async function loadAgents() {
        const agents = await api.request("/api/agents");
        state.agents = Array.isArray(agents) ? agents : [];
        if (!state.agents.some((agent) => agent.id === state.selectedAgentId)) {
            state.selectedAgentId = state.agents[0]?.id || "";
        }
        renderAgents();
        updateAgentOptions();
        updateOverview();
        if (state.selectedAgentId) await selectAgent(state.selectedAgentId);
    }

    async function selectAgent(agentId) {
        state.selectedAgentId = agentId;
        renderAgents();
        try {
            const agent = await api.request(`/api/agents/${encodeURIComponent(agentId)}`);
            renderAgentDetail(agent);
        } catch (error) {
            renderMessage($("agentDetail"), error.message, true);
        }
    }

    function renderAgents() {
        const container = $("agentList");
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
        container.replaceChildren(heading, dl);
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

    async function loadTools() {
        const tools = await api.request("/api/tools");
        state.tools = Array.isArray(tools) ? tools : [];
        if (!state.tools.some((tool) => tool.id === state.selectedToolId)) {
            state.selectedToolId = state.tools[0]?.id || "";
        }
        renderTools();
        updateToolOptions();
        updateOverview();
    }

    function renderTools() {
        const container = $("toolList");
        container.replaceChildren();
        if (!state.tools.length) {
            container.append(emptyState("暂无 Tool，可在右侧注册。"));
            return;
        }
        state.tools.forEach((tool) => {
            const item = element("button", {className: "resource-item", type: "button"});
            item.classList.toggle("active", tool.id === state.selectedToolId);
            item.append(element("strong", {text: tool.name || "未命名 Tool"}));
            item.append(element("span", {text: `${tool.type || "未知类型"} · ${tool.riskLevel || "未知风险"} · ${tool.enabled ? "已启用" : "已停用"}`}));
            item.addEventListener("click", () => {
                state.selectedToolId = tool.id;
                renderTools();
                $("grantToolSelect").value = tool.id;
            });
            container.append(item);
        });
    }

    async function createTool() {
        const payload = {
            name: $("toolName").value.trim(),
            description: $("toolDescription").value.trim(),
            type: $("toolType").value,
            riskLevel: $("toolRiskLevel").value
        };
        if (!payload.name || !payload.description) {
            setStatus($("toolFormStatus"), "请完整填写 Tool 信息。", "error");
            return;
        }
        try {
            await withSubmitState($("createToolBtn"), async () => {
                const created = await api.request("/api/tools", {method: "POST", body: JSON.stringify(payload)});
                state.selectedToolId = created.id || "";
                await loadTools();
                setStatus($("toolFormStatus"), `Tool“${created.name || payload.name}”已注册。`, "success");
            });
        } catch (error) {
            setStatus($("toolFormStatus"), error.message, "error");
        }
    }

    async function grantTool() {
        const toolId = $("grantToolSelect").value;
        const agentId = $("grantAgentSelect").value;
        if (!toolId || !agentId) {
            setStatus($("grantFormStatus"), "请选择 Tool 和 Agent。", "error");
            return;
        }
        try {
            await withSubmitState($("grantToolBtn"), async () => {
                await api.request(`/api/tools/${encodeURIComponent(toolId)}/grants`, {
                    method: "POST",
                    body: JSON.stringify({agentId})
                });
                setStatus($("grantFormStatus"), "授权已生效。", "success");
                if (agentId === state.selectedAgentId) await selectAgent(agentId);
            });
        } catch (error) {
            setStatus($("grantFormStatus"), error.message, "error");
        }
    }

    function updateAgentOptions() {
        [$("grantAgentSelect"), $("runAgentSelect")].forEach((select) => {
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

    function updateOverview() {
        $("overviewAgentCount").textContent = String(state.agents.length);
        $("overviewToolCount").textContent = String(state.tools.length);
        $("overviewRunStatus").textContent = "暂无";
        $("overviewRunTime").textContent = state.agents.length ? "进入运行记录加载数据" : "尚无 Agent";
        $("overviewRuns").replaceChildren(emptyState(state.agents.length ? "进入运行记录查看最近活动。" : "选择或创建 Agent 后查看运行记录。"));
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

    $("loginForm").addEventListener("submit", (event) => {
        event.preventDefault();
        login();
    });
    $("logoutBtn").addEventListener("click", () => logout());
    $("agentForm").addEventListener("submit", (event) => { event.preventDefault(); createAgent(); });
    $("toolForm").addEventListener("submit", (event) => { event.preventDefault(); createTool(); });
    $("grantForm").addEventListener("submit", (event) => { event.preventDefault(); grantTool(); });
    $("refreshAgentsBtn").addEventListener("click", () => loadAgents().catch((error) => setStatus($("globalStatus"), error.message, "error")));
    $("refreshToolsBtn").addEventListener("click", () => loadTools().catch((error) => setStatus($("globalStatus"), error.message, "error")));
    document.querySelectorAll("[data-page]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.page)));
    document.querySelectorAll("[data-navigate]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.navigate)));

    logout("请输入用户名和密码。");
})();
