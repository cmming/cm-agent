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
        modelConfigsPage: "/console/v2/model-configs.html",
        toolsPage: "/console/v2/tools.html",
        chatPage: "/console/v2/chat.html",
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
        modelConfigs: [],
        tools: [],
        localExamples: [],
        selectedAgentId: "",
        selectedAgent: null,
        editingAgentId: "",
        selectedModelConfigId: "",
        selectedModelConfig: null,
        editingModelConfigId: "",
        editingModelConfigOrigin: null,
        modelCatalogItems: [],
        selectedToolId: "",
        editingToolId: "",
        runs: [],
        runCursor: "",
        selectedRunId: "",
        conversations: [],
        selectedConversationId: "",
        conversationAgentId: "",
        pendingApprovals: new Map(),
        approvalSubmissions: new Set(),
        approvalFeedback: new Map(),
        approvalHistory: [],
        approvalHistoryCursor: "",
        approvalHistoryBusy: false,
        approvalHistoryError: "",
        chatBusy: false,
        chatLoading: false,
        auditEvents: [],
        auditCursor: ""
    };
    const toolPublicationLock = core.createToolPublicationLock();
    const approvalDrafts = core.createApprovalDraftStore();
    const toolLoadRevision = core.createLoadRevisionGate();
    const chatLoadRevision = core.createLoadRevisionGate();
    const approvalHistoryRevision = core.createLoadRevisionGate();
    const conversationLoadRevision = core.createLoadRevisionGate();
    const agentDetailRevision = core.createLoadRevisionGate();
    const modelConfigLoadRevision = core.createLoadRevisionGate();
    const modelConfigDetailRevision = core.createLoadRevisionGate();
    const modelCatalogDiscoveryRevision = core.createLoadRevisionGate();
    const localExampleInstallLock = core.createToolPublicationLock();
    const localExampleLoadRevision = core.createLoadRevisionGate();
    const localExampleInstallRevision = core.createKeyedLoadRevisionGate();
    const sessionEpoch = core.createSessionEpochGate();
    const submitStateGuard = core.createSubmitStateGuard();
    let pageNavigationRevision = 0;

    const pageInfo = {
        overviewPage: ["能力总览", "查看当前租户已交付的 Agent 能力与最近活动。"],
        agentsPage: ["Agent 管理", "创建、编辑或删除 Agent，并从模型配置中选择运行模型。"],
        modelConfigsPage: ["模型配置", "管理 Provider、服务地址和默认模型名称。"],
        toolsPage: ["工具治理", "注册 Tool，并向指定 Agent 授予使用权限。"],
        chatPage: ["会话聊天", "选择 Agent，在同一会话中保留上下文并实时查看回答。"],
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
        if (options.value !== undefined) node.value = String(options.value);
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
        modelConfigLoadRevision.invalidate();
        modelConfigDetailRevision.invalidate();
        modelCatalogDiscoveryRevision.invalidate();
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
        modelConfigLoadRevision.invalidate();
        modelConfigDetailRevision.invalidate();
        modelCatalogDiscoveryRevision.invalidate();
        localExampleLoadRevision.invalidate();
        localExampleInstallRevision.invalidateAll();
        state.token = "";
        state.currentUser = null;
        state.agents = [];
        state.modelConfigs = [];
        state.tools = [];
        state.localExamples = [];
        state.selectedAgentId = "";
        state.selectedAgent = null;
        state.selectedModelConfigId = "";
        state.selectedModelConfig = null;
        state.editingModelConfigId = "";
        state.selectedToolId = "";
        state.conversations = [];
        state.selectedConversationId = "";
        state.conversationAgentId = "";
        state.pendingApprovals.clear();
        state.approvalSubmissions.clear();
        state.approvalFeedback.clear();
        approvalDrafts.clear();
        resetApprovalHistory();
        state.chatLoading = false;
        chatLoadRevision.invalidate();
        conversationLoadRevision.invalidate();
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
            Promise.all([loadRuns({append: false}), loadConversations(state.selectedAgentId)])
                .catch((error) => setStatus($("runFormStatus"), error.message, "error"));
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
            if (state.selectedAgentId) {
                await Promise.all([loadRuns({append: false}), loadConversations(state.selectedAgentId)]);
            }
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
                await Promise.all([loadTools(undefined, session), loadModelConfigs(undefined, session)]);
                if (sessionEpoch.isCurrent(session)) await loadAgents(session);
                break;
            case "modelConfigsPage":
                await loadModelConfigs(undefined, session);
                break;
            case "toolsPage":
                await Promise.all([
                    loadAgents(session),
                    loadTools(undefined, session),
                    loadLocalExamples(undefined, false, session)
                ]);
                break;
            case "chatPage":
                await loadAgents(session);
                if (sessionEpoch.isCurrent(session)) await initializeChatPage();
                break;
            case "runsPage":
                await loadAgents(session);
                if (sessionEpoch.isCurrent(session) && state.selectedAgentId) {
                    await Promise.all([
                        loadRuns({append: false}),
                        loadConversations(state.selectedAgentId)
                    ]);
                }
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
        updateAgentModelOptions();
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
            ["模型配置", modelConfigDisplayName(agent.modelProviderId)],
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
        if ($("agentModelConfigId")) {
            const actions = element("div", {className: "tool-actions"});
            const editButton = element("button", {className: "button", type: "button", text: "编辑"});
            editButton.addEventListener("click", () => editAgent(agent));
            const deleteButton = element("button", {className: "button danger", type: "button", text: "删除"});
            deleteButton.addEventListener("click", () => deleteAgent(agent, deleteButton));
            actions.append(editButton, deleteButton);
            container.replaceChildren(heading, dl, toolsSection, actions);
            return;
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

    function modelConfigDisplayName(modelConfigId) {
        const config = state.modelConfigs.find((item) => item.id === modelConfigId);
        if (!config) return modelConfigId || "未配置";
        return `${config.displayName || config.id} · ${config.modelName || "未配置模型"}`;
    }

    function updateAgentModelOptions(selectedId = $("agentModelConfigId")?.value || "") {
        const select = $("agentModelConfigId");
        if (!select) return;
        select.replaceChildren();
        const placeholder = element("option", {text: "请选择已启用的模型配置"});
        placeholder.value = "";
        select.append(placeholder);
        state.modelConfigs.forEach((config) => {
            const option = element("option", {
                text: `${config.displayName || config.id} · ${config.modelName || "未配置模型"}${config.enabled ? "" : "（已停用）"}`
            });
            option.value = config.id;
            option.disabled = !config.enabled;
            select.append(option);
        });
        if (selectedId && state.modelConfigs.some((config) => config.id === selectedId)) {
            select.value = selectedId;
        } else if (state.modelConfigs.some((config) => config.enabled)) {
            select.value = state.modelConfigs.find((config) => config.enabled).id;
        }
    }

    function editAgent(agent) {
        state.editingAgentId = agent.id;
        $("agentName").value = agent.name || "";
        $("systemPrompt").value = agent.systemPrompt || "";
        $("agentEnabled").checked = agent.enabled === true;
        updateAgentModelOptions(agent.modelProviderId || "");
        $("agentFormEyebrow").textContent = "编辑";
        $("agentFormTitle").textContent = "更新 Agent";
        $("createAgentBtn").textContent = "保存修改";
        $("cancelAgentEditBtn").hidden = false;
        setStatus($("agentFormStatus"), `正在编辑“${agent.name || agent.id}”。`, "neutral");
        $("agentName").focus();
    }

    function resetAgentForm() {
        state.editingAgentId = "";
        const form = $("agentForm");
        if (!form) return;
        form.reset();
        $("agentEnabled").checked = true;
        updateAgentModelOptions();
        $("agentFormEyebrow").textContent = "新建";
        $("agentFormTitle").textContent = "创建 Agent";
        $("createAgentBtn").textContent = "创建 Agent";
        $("cancelAgentEditBtn").hidden = true;
        setStatus($("agentFormStatus"));
    }

    async function createAgent() {
        const modelConfigSelect = $("agentModelConfigId");
        const payload = {
            name: $("agentName").value.trim(),
            systemPrompt: $("systemPrompt").value.trim()
        };
        if (modelConfigSelect) {
            payload.modelConfigId = modelConfigSelect.value;
            payload.enabled = $("agentEnabled").checked;
        } else {
            // v1 仍允许输入名称；服务端只会在当前租户唯一匹配已启用配置时接受它。
            payload.modelName = $("agentModelName").value.trim();
        }
        if (!payload.name || !payload.systemPrompt || !(payload.modelConfigId || payload.modelName)) {
            setStatus($("agentFormStatus"), "请完整填写 Agent 信息。", "error");
            return;
        }
        const editingId = state.editingAgentId;
        const path = editingId ? `/api/agents/${encodeURIComponent(editingId)}` : "/api/agents";
        const method = editingId ? "PUT" : "POST";
        try {
            await withSubmitState($("createAgentBtn"), async () => {
                const saved = await api.request(path, {method, body: JSON.stringify(payload)});
                state.selectedAgentId = saved.id || editingId || "";
                await loadAgents();
                if (modelConfigSelect) resetAgentForm();
                setStatus($("agentFormStatus"), editingId
                    ? `Agent“${saved.name || payload.name}”已更新。`
                    : `Agent“${saved.name || payload.name}”已创建。`, "success");
            });
        } catch (error) {
            setStatus($("agentFormStatus"), error.message, "error");
        }
    }

    async function deleteAgent(agent, button) {
        if (!window.confirm(`确认删除 Agent“${agent.name || agent.id}”吗？已有运行或会话历史时服务端会拒绝删除。`)) {
            return;
        }
        try {
            await withSubmitState(button, async () => {
                await api.request(`/api/agents/${encodeURIComponent(agent.id)}`, {method: "DELETE"});
                if (state.editingAgentId === agent.id) resetAgentForm();
                state.selectedAgentId = "";
                state.selectedAgent = null;
                await loadAgents();
                setStatus($("globalStatus"), `Agent“${agent.name || agent.id}”已删除。`, "success");
            });
        } catch (error) {
            setStatus($("globalStatus"), error.message, "error");
        }
    }

    async function loadModelConfigs(
        revision = modelConfigLoadRevision.issue(),
        session = sessionEpoch.capture()
    ) {
        const modelConfigs = await api.request("/api/model-configs");
        if (!sessionEpoch.isCurrent(session) || !modelConfigLoadRevision.isCurrent(revision)) {
            return false;
        }
        state.modelConfigs = Array.isArray(modelConfigs) ? modelConfigs : [];
        if (!state.modelConfigs.some((config) => config.id === state.selectedModelConfigId)) {
            state.selectedModelConfigId = state.modelConfigs[0]?.id || "";
        }
        if (state.editingModelConfigId
                && !state.modelConfigs.some((config) => config.id === state.editingModelConfigId)) {
            resetModelConfigForm();
        }
        renderModelConfigs();
        if (state.selectedModelConfigId) {
            await selectModelConfig(state.selectedModelConfigId, undefined, session);
        } else {
            state.selectedModelConfig = null;
            renderMessage($("modelConfigDetail"), "选择模型配置查看详情。");
        }
        return true;
    }

    async function selectModelConfig(
        modelConfigId,
        revision = modelConfigDetailRevision.issue(),
        session = sessionEpoch.capture()
    ) {
        state.selectedModelConfigId = modelConfigId;
        renderModelConfigs();
        try {
            const config = await api.request(`/api/model-configs/${encodeURIComponent(modelConfigId)}`);
            if (!sessionEpoch.isCurrent(session)
                    || !modelConfigDetailRevision.isCurrent(revision)
                    || state.selectedModelConfigId !== modelConfigId) {
                return false;
            }
            state.selectedModelConfig = config;
            renderModelConfigDetail(config);
            return true;
        } catch (error) {
            if (!sessionEpoch.isCurrent(session)
                    || !modelConfigDetailRevision.isCurrent(revision)
                    || state.selectedModelConfigId !== modelConfigId) {
                return false;
            }
            state.selectedModelConfig = null;
            renderMessage($("modelConfigDetail"), error.message, true);
            return false;
        }
    }

    function renderModelConfigs() {
        const container = $("modelConfigList");
        if (!container) return;
        container.replaceChildren();
        if (!state.modelConfigs.length) {
            container.append(emptyState("暂无模型配置，可在右侧创建。"));
            return;
        }
        state.modelConfigs.forEach((config) => {
            const button = element("button", {className: "resource-item", type: "button"});
            button.classList.toggle("active", config.id === state.selectedModelConfigId);
            button.append(element("strong", {text: config.displayName || "未命名模型配置"}));
            button.append(element("span", {
                text: `${providerLabel(config.providerType)} · ${config.modelName || "未配置模型"} · ${config.enabled ? "已启用" : "已停用"}`
            }));
            button.addEventListener("click", () => selectModelConfig(config.id));
            container.append(button);
        });
    }

    function renderModelConfigDetail(config) {
        const container = $("modelConfigDetail");
        if (!container) return;
        const heading = element("div", {className: "panel-heading"});
        const titleGroup = element("div");
        titleGroup.append(element("p", {className: "eyebrow", text: "模型配置详情"}));
        titleGroup.append(element("h2", {text: config.displayName || "未命名模型配置"}));
        const enabledBadge = element("span", {
            className: "status-badge",
            text: config.enabled ? "已启用" : "已停用"
        });
        enabledBadge.dataset.tone = config.enabled ? "success" : "neutral";
        heading.append(titleGroup, enabledBadge);
        const details = definitionList([
            ["ID", config.id],
            ["Provider", providerLabel(config.providerType)],
            ["基础地址", config.baseUrl],
            ["默认模型", config.modelName],
            ["状态", config.enabled ? "已启用" : "已停用"],
            ["凭据管理", "已加密保存；仅支持轮换，不支持回显"]
        ]);
        const actions = element("div", {className: "tool-actions"});
        const editButton = element("button", {className: "button", type: "button", text: "编辑"});
        editButton.addEventListener("click", () => editModelConfig(config));
        const deleteButton = element("button", {className: "button danger", type: "button", text: "删除"});
        deleteButton.addEventListener("click", () => deleteModelConfig(config, deleteButton));
        actions.append(editButton, deleteButton);
        container.replaceChildren(heading, details, actions);
    }

    function editModelConfig(config) {
        state.editingModelConfigId = config.id;
        state.editingModelConfigOrigin = {
            providerType: config.providerType || "OPENAI_COMPATIBLE",
            baseUrl: config.baseUrl || ""
        };
        $("modelConfigDisplayName").value = config.displayName || "";
        $("modelConfigProviderType").value = config.providerType || "OPENAI_COMPATIBLE";
        $("modelConfigBaseUrl").value = config.baseUrl || "";
        $("modelConfigModelName").value = config.modelName || "";
        $("modelConfigApiKey").value = "";
        $("modelConfigApiKey").required = false;
        $("modelConfigApiKeyHelp").textContent = "留空保留当前 API Key；填写新值将轮换密钥，现有密钥不会回显。";
        $("modelConfigEnabled").checked = config.enabled === true;
        setModelCatalogItems([]);
        updateDiscoverModelCatalogButton();
        $("modelConfigFormEyebrow").textContent = "编辑";
        $("modelConfigFormTitle").textContent = "更新模型配置";
        $("saveModelConfigBtn").textContent = "保存修改";
        $("cancelModelConfigEditBtn").hidden = false;
        setStatus($("modelConfigFormStatus"), `正在编辑“${config.displayName || config.id}”。`, "neutral");
        $("modelConfigDisplayName").focus();
    }

    function resetModelConfigForm() {
        state.editingModelConfigId = "";
        state.editingModelConfigOrigin = null;
        const form = $("modelConfigForm");
        if (!form) return;
        form.reset();
        $("modelConfigProviderType").value = "OPENAI_COMPATIBLE";
        $("modelConfigEnabled").checked = true;
        $("modelConfigApiKey").value = "";
        $("modelConfigApiKey").required = true;
        $("modelConfigApiKeyHelp").textContent = "创建时必填。平台仅加密保存，不会在详情、列表或编辑表单中回显。";
        setModelCatalogItems([]);
        updateDiscoverModelCatalogButton();
        $("modelConfigFormEyebrow").textContent = "新建";
        $("modelConfigFormTitle").textContent = "创建模型配置";
        $("saveModelConfigBtn").textContent = "创建模型配置";
        $("cancelModelConfigEditBtn").hidden = true;
        setStatus($("modelConfigFormStatus"));
    }

    function modelCatalogCanUseSavedCredential() {
        const origin = state.editingModelConfigOrigin;
        return Boolean(state.editingModelConfigId && origin
            && $("modelConfigProviderType").value === origin.providerType
            && $("modelConfigBaseUrl").value.trim() === origin.baseUrl);
    }

    function updateDiscoverModelCatalogButton() {
        const button = $("discoverModelNamesBtn");
        if (!button) return;
        const hasRequiredConnection = Boolean($("modelConfigProviderType").value && $("modelConfigBaseUrl").value.trim());
        const hasDraftCredential = Boolean($("modelConfigApiKey").value.trim());
        const canUseSavedCredential = modelCatalogCanUseSavedCredential();
        button.disabled = !hasRequiredConnection || (!hasDraftCredential && !canUseSavedCredential);
        button.textContent = canUseSavedCredential && !hasDraftCredential ? "使用已保存凭据获取模型列表" : "获取模型列表";
    }

    function setModelCatalogItems(items) {
        state.modelCatalogItems = Array.isArray(items) ? [...new Set(items.filter((item) => typeof item === "string" && item.trim()))]
            .sort((first, second) => first.localeCompare(second)) : [];
        const options = $("modelCatalogOptions");
        if (!options) return;
        options.replaceChildren(...state.modelCatalogItems.map((item) => element("option", {value: item})));
    }

    async function discoverModelCatalog() {
        const providerType = $("modelConfigProviderType").value;
        const baseUrl = $("modelConfigBaseUrl").value.trim();
        const apiKey = $("modelConfigApiKey").value;
        const canUseSavedCredential = modelCatalogCanUseSavedCredential() && !apiKey.trim();
        if (!providerType || !baseUrl || (!apiKey.trim() && !canUseSavedCredential)) {
            setStatus($("modelCatalogStatus"), "请先填写 Provider、服务地址和 API Key；仅未改动地址的已保存配置可使用原凭据。", "error");
            updateDiscoverModelCatalogButton();
            return;
        }
        const revision = modelCatalogDiscoveryRevision.issue();
        const path = canUseSavedCredential
            ? `/api/model-configs/${encodeURIComponent(state.editingModelConfigId)}/discover-models`
            : "/api/model-configs/discover-models";
        const options = canUseSavedCredential ? {method: "POST"} : {
            method: "POST",
            body: JSON.stringify({providerType, baseUrl, apiKey})
        };
        try {
            await withSubmitState($("discoverModelNamesBtn"), async () => {
                setStatus($("modelCatalogStatus"), "正在从模型服务获取目录…", "neutral");
                const response = await api.request(path, options);
                if (!modelCatalogDiscoveryRevision.isCurrent(revision)) return;
                setModelCatalogItems(response?.items);
                const message = state.modelCatalogItems.length
                    ? `已获取 ${state.modelCatalogItems.length} 个模型名称，可搜索选择或继续手动填写。`
                    : "模型服务未返回可用模型名称，可继续手动填写。";
                setStatus($("modelCatalogStatus"), message, state.modelCatalogItems.length ? "success" : "neutral");
            });
        } catch (error) {
            if (!modelCatalogDiscoveryRevision.isCurrent(revision)) return;
            setStatus($("modelCatalogStatus"), `${error.message} 可继续手动填写模型名称。`, "error");
        } finally {
            updateDiscoverModelCatalogButton();
        }
    }

    async function submitModelConfig() {
        const payload = {
            providerType: $("modelConfigProviderType").value,
            displayName: $("modelConfigDisplayName").value.trim(),
            baseUrl: $("modelConfigBaseUrl").value.trim(),
            modelName: $("modelConfigModelName").value.trim(),
            enabled: $("modelConfigEnabled").checked
        };
        if (!payload.providerType || !payload.displayName || !payload.baseUrl || !payload.modelName) {
            setStatus($("modelConfigFormStatus"), "请完整填写模型配置信息。", "error");
            return;
        }
        const editingId = state.editingModelConfigId;
        const apiKey = $("modelConfigApiKey").value;
        if (!editingId && !apiKey.trim()) {
            setStatus($("modelConfigFormStatus"), "创建模型配置时必须填写 API Key。", "error");
            return;
        }
        if (apiKey) payload.apiKey = apiKey;
        const path = editingId
            ? `/api/model-configs/${encodeURIComponent(editingId)}`
            : "/api/model-configs";
        const method = editingId ? "PUT" : "POST";
        try {
            await withSubmitState($("saveModelConfigBtn"), async () => {
                const saved = await api.request(path, {method, body: JSON.stringify(payload)});
                const message = editingId
                    ? `模型配置“${saved.displayName || payload.displayName}”已更新。`
                    : `模型配置“${saved.displayName || payload.displayName}”已创建。`;
                state.selectedModelConfigId = saved.id || editingId || "";
                resetModelConfigForm();
                const revision = modelConfigLoadRevision.completeWrite();
                const reloaded = await loadModelConfigs(revision);
                if (reloaded) setStatus($("modelConfigFormStatus"), message, "success");
            });
        } catch (error) {
            setStatus($("modelConfigFormStatus"), error.message, "error");
        }
    }

    async function deleteModelConfig(config, button) {
        if (!window.confirm(`确认删除模型配置“${config.displayName || config.id}”吗？仍被 Agent 引用时服务端会拒绝删除。`)) {
            return;
        }
        try {
            await withSubmitState(button, async () => {
                await api.request(`/api/model-configs/${encodeURIComponent(config.id)}`, {method: "DELETE"});
                if (state.editingModelConfigId === config.id) resetModelConfigForm();
                state.selectedModelConfigId = "";
                state.selectedModelConfig = null;
                const revision = modelConfigLoadRevision.completeWrite();
                const reloaded = await loadModelConfigs(revision);
                if (reloaded) setStatus($("globalStatus"), `模型配置“${config.displayName || config.id}”已删除。`, "success");
            });
        } catch (error) {
            setStatus($("globalStatus"), error.message, "error");
        }
    }

    function providerLabel(providerType) {
        if (providerType === "DASHSCOPE_NATIVE") return "DashScope Native";
        if (providerType === "OPENAI_COMPATIBLE") return "OpenAI Compatible";
        return providerType || "未知 Provider";
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
        [$("grantAgentSelect"), $("runAgentSelect"), $("chatAgentSelect")].filter(Boolean).forEach((select) => {
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
                state.selectedAgentId = agentId;
                state.selectedRunId = "";
                const conversationId = await ensureConversation(agentId);
                let result = null;
                let streamedOutput = "";
                renderStreamingRunDetail(input);
                setStatus($("runFormStatus"), "运行中，正在接收模型输出…", "neutral");
                await api.stream(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/messages/stream`, {
                    method: "POST",
                    body: JSON.stringify({input})
                }, (event) => {
                    if (event.type === "delta") {
                        const delta = String(event.data?.delta || "");
                        if (!delta) return;
                        streamedOutput += delta;
                        appendStreamingOutput(delta);
                        return;
                    }
                    if (event.type === "completed") {
                        result = event.data;
                        return;
                    }
                    if (event.type === "error") {
                        const code = event.data?.code ? `（错误码：${event.data.code}）` : "";
                        const errorId = event.data?.errorId ? `（错误编号：${event.data.errorId}）` : "";
                        throw new Error(`${event.data?.message || "运行失败"}${code}${errorId}`);
                    }
                });
                if (!result) throw new Error("运行未返回最终结果，请刷新运行记录确认状态。");
                const runResult = result.run || result;
                // 最终持久化结果是权威内容；用于兼容不支持增量的 Runtime，并校正可能被模型合并的文本块边界。
                if (String(runResult.output || "") !== streamedOutput) {
                    setStreamingOutput(String(runResult.output || ""));
                }
                await loadConversations(agentId);
                await loadRuns({append: false});
                if (runResult?.runId) await loadRunDetail(runResult.runId);
                const meta = core.statusMeta(runResult?.status);
                setStatus($("runFormStatus"), `运行已结束：${meta.label}。`, runResult?.status === "SUCCEEDED" ? "success" : "error");
            });
        } catch (error) {
            setStatus($("runFormStatus"), error.message, "error");
        }
    }

    async function ensureConversation(agentId) {
        if (state.conversationAgentId !== agentId) {
            state.selectedConversationId = "";
            await loadConversations(agentId);
        }
        if (state.selectedConversationId) return state.selectedConversationId;
        const created = await api.request(`/api/agents/${encodeURIComponent(agentId)}/conversations`, {
            method: "POST"
        });
        state.selectedConversationId = created.id;
        await loadConversations(agentId);
        return created.id;
    }

    async function loadConversations(agentId) {
        if (!agentId) return;
        const session = sessionEpoch.capture();
        const revision = conversationLoadRevision.issue();
        const page = await api.request(`/api/agents/${encodeURIComponent(agentId)}/conversations?limit=50`);
        if (!sessionEpoch.isCurrent(session) || !conversationLoadRevision.isCurrent(revision)
                || state.selectedAgentId !== agentId) return;
        state.conversations = Array.isArray(page?.items) ? page.items : [];
        state.conversationAgentId = agentId;
        if (!state.conversations.some((item) => item.id === state.selectedConversationId)) {
            state.selectedConversationId = state.conversations[0]?.id || "";
        }
        const select = $("runConversationSelect");
        if (select) {
            select.replaceChildren();
            if (!state.conversations.length) {
                select.append(element("option", {text: "发送时自动创建", value: ""}));
            } else {
                state.conversations.forEach((conversation) => select.append(element("option", {
                    text: conversation.title || "新会话",
                    value: conversation.id
                })));
            }
            select.value = state.selectedConversationId;
        }
        renderChatConversations();
    }

    async function createConversation() {
        const agentId = $("runAgentSelect")?.value;
        if (!agentId) return;
        const created = await api.request(`/api/agents/${encodeURIComponent(agentId)}/conversations`, {
            method: "POST"
        });
        state.selectedConversationId = created.id;
        await loadConversations(agentId);
        await loadConversationMessages(agentId, created.id);
        setStatus($("runFormStatus"), "新会话已创建。", "success");
    }

    async function loadConversationMessages(agentId, conversationId) {
        if (!conversationId || !$("runDetail")) return;
        const page = await api.request(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/messages?limit=200`);
        const container = $("runDetail");
        const messages = Array.isArray(page?.items) ? page.items : [];
        container.replaceChildren();
        if (!messages.length) {
            container.append(emptyState("当前会话还没有消息。"));
            return;
        }
        messages.forEach((message) => {
            const section = element("section", {className: "detail-section conversation-message"});
            section.append(element("h3", {text: message.role === "USER" ? "用户" : "Agent"}));
            (message.contentBlocks || []).forEach((block) => {
                if (block.type === "TEXT") {
                    const output = element("div", {className: "markdown-output"});
                    renderMarkdown(output, String(block.text || ""));
                    section.append(output);
                } else if (block.type === "THINKING") {
                    section.append(element("p", {
                        className: "run-copy-block",
                        text: `思考过程（模型提供）：${block.text || ""}`
                    }));
                } else {
                    section.append(element("p", {
                        className: "run-copy-block",
                        text: block.type === "TOOL_USE"
                            ? `工具调用：${block.toolName || "—"} · ${block.text || ""}`
                            : `工具结果：${block.status || "—"} · ${block.text || ""}`
                    }));
                }
            });
            container.append(section);
        });
    }

    async function initializeChatPage() {
        if (!$("chatPage")) return;
        if (!state.selectedAgentId) {
            renderChatConversations();
            renderChatWelcome("暂无可用 Agent，请先创建并启用一个 Agent。");
            return;
        }
        await loadConversations(state.selectedAgentId);
        if (state.selectedConversationId) {
            await loadChatMessages(state.selectedAgentId, state.selectedConversationId);
        } else {
            renderChatWelcome("当前 Agent 还没有会话，发送第一条消息即可开始。");
        }
    }

    function renderChatConversations() {
        const container = $("chatConversationList");
        if (!container) return;
        container.replaceChildren();
        if (!state.selectedAgentId) {
            container.append(emptyState("请先选择可用 Agent。"));
            return;
        }
        if (!state.conversations.length) {
            container.append(emptyState("暂无会话，点击“新会话”或发送消息开始。"));
            return;
        }
        state.conversations.forEach((conversation) => {
            const button = element("button", {className: "chat-conversation-item", type: "button"});
            button.classList.toggle("active", conversation.id === state.selectedConversationId);
            const title = conversation.title || "新会话";
            button.append(
                element("strong", {text: title}),
                element("span", {text: core.formatDateTime(conversation.updatedAt)})
            );
            button.addEventListener("click", () => selectChatConversation(conversation.id));
            container.append(button);
        });
    }

    async function selectChatConversation(conversationId) {
        if (!conversationId || state.chatBusy) return;
        state.selectedConversationId = conversationId;
        renderChatConversations();
        await loadChatMessages(state.selectedAgentId, conversationId);
    }

    async function createChatConversation() {
        const agentId = $("chatAgentSelect")?.value;
        if (!agentId) {
            setStatus($("chatFormStatus"), "请先选择 Agent。", "error");
            return;
        }
        const created = await api.request(`/api/agents/${encodeURIComponent(agentId)}/conversations`, {
            method: "POST"
        });
        state.selectedAgentId = agentId;
        state.selectedConversationId = created.id;
        await loadConversations(agentId);
        await loadChatMessages(agentId, created.id);
        setStatus($("chatFormStatus"), "新会话已创建。", "success");
    }

    async function loadChatMessages(agentId, conversationId) {
        const session = sessionEpoch.capture();
        const revision = chatLoadRevision.issue();
        const isCurrent = () => sessionEpoch.isCurrent(session) && chatLoadRevision.isCurrent(revision)
                && state.selectedAgentId === agentId && state.selectedConversationId === conversationId;
        if (!isCurrent()) return false;
        if (!conversationId || !$("chatMessageList")) {
            state.pendingApprovals.clear();
            resetApprovalHistory();
            renderApprovalHistory();
            renderChatApprovals();
            return false;
        }
        const base = `/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}`;
        // 查询结束前不开放发送，防止页面刷新时短暂绕过待审批发送锁。
        state.chatLoading = true;
        state.pendingApprovals.clear();
        resetApprovalHistory();
        const historyRevision = approvalHistoryRevision.issue();
        state.approvalHistoryBusy = true;
        renderApprovalHistory();
        renderChatApprovals();
        try {
            const [page, approvalPage, historyResult] = await Promise.all([
                api.request(`${base}/messages?limit=200`),
                api.request(`${base}/approvals?status=PENDING`),
                // 历史读取失败不能伪装为空历史，也不影响已确认安全的待办发送状态。
                api.request(`${base}/approvals/history?limit=20`).then((page) => ({page}), (error) => ({error}))
            ]);
            if (!isCurrent()) return false;
            renderChatMessages(Array.isArray(page?.items) ? page.items : []);
            state.pendingApprovals = new Map((Array.isArray(approvalPage?.items) ? approvalPage.items : [])
                .map((approval) => [approval.approvalId, approval]));
            if (approvalHistoryRevision.isCurrent(historyRevision)) {
                state.approvalHistory = core.mergeApprovalHistory([], historyResult.page?.items || [], agentId, conversationId);
                state.approvalHistoryCursor = historyResult.page?.nextCursor || "";
                state.approvalHistoryError = historyResult.error?.message || "";
                state.approvalHistoryBusy = false;
            }
            state.chatLoading = false;
            renderChatApprovals();
            renderApprovalHistory();
            return true;
        } catch (error) {
            if (!isCurrent()) return false;
            // 无法确认服务端状态时保持发送关闭，用户刷新后再建立权威状态。
            setStatus($("chatFormStatus"), `${error.message} 请刷新会话后重试。`, "error");
            state.approvalHistoryBusy = false;
            state.approvalHistoryError = "会话未能完整加载，请重新加载当前会话后查看审批历史。";
            renderApprovalHistory();
            throw error;
        }
    }

    function renderChatApprovals() {
        const region = $("chatApprovalRegion");
        const container = $("chatApprovalList");
        if (!region || !container) return;
        const approvals = [...state.pendingApprovals.values()].filter((approval) => approval.status === "PENDING"
            || state.approvalSubmissions.has(approval.approvalId)
            || state.approvalFeedback.get(approval.approvalId)?.phase === "UNKNOWN");
        const focused = document.activeElement;
        const focusedApproval = focused?.closest(".chat-approval-card")?.dataset.approvalId;
        const focusedId = focused?.id;
        region.hidden = approvals.length === 0;
        if ($("chatApprovalHeading")) $("chatApprovalHeading").textContent = approvals.some((item) => item.status === "PENDING")
            ? "待审批操作" : "审批处理记录";
        container.replaceChildren();
        approvals.forEach((approval) => container.append(createApprovalCard(approval)));
        updateChatSendAvailability();
        // 流事件重绘卡片时保留键盘位置；控件变为禁用后将焦点交给同一请求的状态提示。
        if (focusedApproval) {
            const replacement = focusedId ? $(focusedId) : null;
            (replacement && !replacement.matches(":disabled") ? replacement : $(`approval-status-${focusedApproval}`))?.focus({preventScroll: true});
        }
    }

    function createApprovalCard(approval) {
        const submitting = state.approvalSubmissions.has(approval.approvalId);
        const feedback = state.approvalFeedback.get(approval.approvalId);
        const ui = core.approvalUiState(approval, feedback?.phase);
        const card = element("article", {className: "chat-approval-card"});
        card.dataset.approvalId = approval.approvalId;
        card.dataset.status = approval.status;
        card.setAttribute("aria-busy", String(submitting));
        const title = element("div", {className: "chat-approval-card-title"});
        title.append(
            element("strong", {text: ui.label}),
            element("span", {text: `发起人：${approval.requestedByDisplayName || "—"} · 过期：${core.formatDateTime(approval.expiresAt)}`})
        );
        card.append(title);
        const explanation = element("p", {className: "chat-approval-explanation", text: ui.message});
        card.append(explanation);
        const form = element("form", {className: "chat-approval-form"});
        const items = Array.isArray(approval.items) ? approval.items : [];
        const choices = new Map(approvalDrafts.read(approval).map((item) => [item.itemId, item.decision]));
        const details = element("details", {className: "chat-approval-details"});
        details.open = !ui.terminal;
        details.append(element("summary", {text: ui.terminal ? `查看 ${items.length} 项调用与决定` : `核对 ${items.length} 项调用参数`}),
            element("p", {className: "chat-approval-call", text: `运行编号：${approval.runId || "—"}`}));
        items.forEach((item) => details.append(createApprovalItem(approval,
            {...item, decision: item.decision || choices.get(item.itemId)}, submitting || !ui.editable)));
        form.append(details);
        const actions = element("div", {className: "chat-approval-actions"});
        if (approval.canDecide && approval.status === "PENDING" && feedback?.phase !== "UNKNOWN") {
            const selectionSummary = element("p", {className: "chat-approval-selection"});
            selectionSummary.id = `approval-selection-${approval.approvalId}`;
            selectionSummary.setAttribute("role", "status");
            selectionSummary.setAttribute("aria-live", "polite");
            const submit = element("button", {className: "button primary compact", type: "submit"});
            submit.setAttribute("aria-describedby", selectionSummary.id);
            submit.id = `approval-submit-${approval.approvalId}`;
            const updateChoices = () => {
                const decisions = collectApprovalChoices(approval, form);
                approvalDrafts.write(approval, decisions);
                updateApprovalSubmitAvailability(approval, decisions, submit, selectionSummary, submitting);
                if (submitting) submit.textContent = ui.label;
            };
            if (items.length > 1) {
                for (const [decision, label] of [["APPROVE", "全部选为允许"], ["DENY", "全部选为拒绝"]]) {
                    const button = element("button", {className: "button ghost compact", type: "button", text: label});
                    button.disabled = submitting;
                    button.addEventListener("click", () => { setApprovalChoices(form, decision); updateChoices(); });
                    actions.append(button);
                }
                form.append(element("p", {className: "chat-approval-batch-note", text: "批量按钮仅更改选择，不会提交；请核对下方汇总后再确认。"}));
            }
            form.addEventListener("change", updateChoices);
            form.append(selectionSummary);
            actions.append(submit);
            updateChoices();
            form.addEventListener("submit", (event) => {
                event.preventDefault();
                const interactionSession = sessionEpoch.capture();
                submitApprovalDecision(approval, form).catch((error) => {
                    if (!sessionEpoch.isCurrent(interactionSession) || state.selectedAgentId !== approval.agentId
                            || state.selectedConversationId !== approval.conversationId) return;
                    const previous = state.approvalFeedback.get(approval.approvalId);
                    state.approvalFeedback.set(approval.approvalId, {phase: previous?.phase === "UNKNOWN" ? "UNKNOWN" : "ERROR", message: error.message, tone: "error"});
                    // 404 权威查询可能已经移除卡片，此时仍需在会话区域保留可读错误和错误编号。
                    if (!state.pendingApprovals.has(approval.approvalId)) setStatus($("chatFormStatus"), error.message, "error");
                    renderChatApprovals();
                });
            });
        } else if (ui.terminal) {
            actions.append(element("p", {className: "chat-approval-readonly", text: `决定时间：${core.formatDateTime(approval.decidedAt)}`}));
        }
        if (!submitting && (!ui.editable || feedback?.phase === "ERROR")) {
            const refresh = element("button", {className: "button ghost compact", type: "button", text: "刷新审批状态"});
            refresh.id = `approval-refresh-${approval.approvalId}`;
            refresh.addEventListener("click", () => refreshApprovalFromCard(approval));
            actions.append(refresh);
        }
        form.append(actions);
        card.append(form);
        const status = element("p", {className: "form-status chat-approval-status", text: feedback?.message || ""});
        status.id = `approval-status-${approval.approvalId}`;
        status.tabIndex = -1;
        status.dataset.tone = feedback?.tone || "neutral";
        status.setAttribute("role", feedback?.tone === "error" ? "alert" : "status");
        status.setAttribute("aria-live", feedback?.tone === "error" ? "assertive" : "polite");
        card.append(status);
        return card;
    }

    function createApprovalItem(approval, item, disabled) {
        const fieldset = element("fieldset", {className: "chat-approval-item"});
        fieldset.dataset.itemId = item.itemId;
        fieldset.disabled = disabled || !approval.canDecide || approval.status !== "PENDING";
        const legend = element("legend");
        legend.append(
            element("strong", {text: item.toolName || "未命名工具"}),
            element("span", {className: "risk-badge chat-approval-risk", text: item.riskLevel || "HIGH"})
        );
        const callId = element("p", {className: "chat-approval-call", text: `调用标识：${item.toolCallId || "—"}`});
        const summaryLabel = element("p", {className: "chat-approval-parameter-label", text: "调用参数（已脱敏，仅供核对，不可编辑）"});
        const summary = element("pre", {className: "chat-approval-summary", text: item.inputSummary || "未提供可展示参数。"});
        const choices = element("div", {className: "chat-approval-decisions"});
        [
            ["APPROVE", "允许本次调用"],
            ["DENY", "拒绝本次调用"]
        ].forEach(([value, label]) => {
            const wrapper = element("label");
            const input = element("input", {type: "radio", value});
            input.id = `approval-choice-${approval.approvalId}-${item.itemId}-${value}`;
            input.name = `approval-${approval.approvalId}-${item.itemId}`;
            input.dataset.itemId = item.itemId;
            if (item.decision === value) input.checked = true;
            wrapper.append(input, document.createTextNode(label));
            choices.append(wrapper);
        });
        fieldset.append(legend, callId, summaryLabel, summary, choices);
        return fieldset;
    }

    function setApprovalChoices(form, decision) {
        form.querySelectorAll(`input[type="radio"][value="${decision}"]`).forEach((input) => {
            input.checked = true;
        });
    }

    function collectApprovalChoices(approval, form) {
        return (approval.items || []).map((item) => {
            const selected = form.querySelector(`input[data-item-id="${item.itemId}"]:checked`);
            return selected ? {itemId: item.itemId, decision: selected.value} : null;
        }).filter(Boolean);
    }

    function updateApprovalSubmitAvailability(approval, decisions, submit, summary, submitting) {
        const selection = core.summarizeApprovalChoices(approval.items, decisions);
        summary.textContent = `已选择 ${selection.selected}/${selection.total} 项 · 允许 ${selection.approved} 项 · 拒绝 ${selection.denied} 项`;
        submit.disabled = submitting || !selection.ready;
        submit.textContent = submitting ? "正在提交决定…" : selection.submitLabel;
    }

    async function submitApprovalDecision(approval, form) {
        if (state.approvalSubmissions.has(approval.approvalId)) return;
        const session = sessionEpoch.capture();
        const isCurrent = () => sessionEpoch.isCurrent(session) && state.selectedAgentId === approval.agentId
                && state.selectedConversationId === approval.conversationId;
        if (!isCurrent()) return;
        const decisions = collectApprovalChoices(approval, form);
        const payload = core.buildApprovalDecisionPayload(approval, decisions);
        state.approvalSubmissions.add(approval.approvalId);
        state.approvalFeedback.set(approval.approvalId, {phase: "SUBMITTING", message: "正在提交审批决定，请勿重复点击。"});
        renderChatApprovals();
        $(`approval-status-${approval.approvalId}`)?.focus({preventScroll: true});
        const streamMessage = appendChatStreamingMessage();
        let streamedOutput = "";
        let completed = false;
        let nextApproval = null;
        let decisionAccepted = false;
        try {
            const base = `/api/agents/${encodeURIComponent(approval.agentId)}/conversations/${encodeURIComponent(approval.conversationId)}`;
            await api.stream(`${base}/approvals/${encodeURIComponent(approval.approvalId)}/decision/stream`, {
                method: "POST",
                body: JSON.stringify(payload)
            }, (event) => {
                if (event.type === "approval-decision") {
                    decisionAccepted = true;
                    approvalDrafts.remove(approval.approvalId);
                    if (isCurrent()) {
                        state.pendingApprovals.set(approval.approvalId, {...approval, ...event.data, canDecide: false,
                            items: approval.items.map((item) => ({...item, decision: payload.decisions.find((choice) => choice.itemId === item.itemId)?.decision}))});
                        state.approvalFeedback.set(approval.approvalId, {phase: "RESUMING", message: "决定已接受，正在处理原运行；无需再次提交。"});
                        renderChatApprovals();
                    }
                } else if (event.type === "delta") {
                    streamedOutput += String(event.data?.delta || "");
                    if (isCurrent()) renderMarkdown(streamMessage.output, streamedOutput);
                } else if (event.type === "progress") {
                    if (isCurrent()) renderChatProgress(streamMessage, event.data);
                } else if (event.type === "approval-required") {
                    nextApproval = event.data;
                } else if (event.type === "completed") {
                    completed = true;
                } else if (event.type === "error") {
                    throw streamEventError(event.data, "审批恢复失败");
                }
            });
            if (!completed && !nextApproval) {
                throw new Error("审批恢复未返回最终状态，请刷新会话确认。");
            }
            if (isCurrent()) {
                await loadConversations(approval.agentId);
                await refreshApprovalState(approval, isCurrent);
                if (isCurrent()) state.approvalFeedback.set(approval.approvalId, {phase: "DONE",
                    message: nextApproval ? "本次决定已处理，运行又提出了新的调用，请继续核对新审批。" : "本次审批流程已结束，执行结果请查看会话回答或运行记录。", tone: "success"});
            }
        } catch (error) {
            if (isCurrent()) {
                try {
                    await refreshApprovalState(approval, isCurrent);
                } catch {
                    if (isCurrent()) {
                        // 网络结果不确定时禁止盲目重试；下一次页面刷新再查询服务端权威状态。
                        state.pendingApprovals.set(approval.approvalId, {...approval, canDecide: false});
                        state.chatLoading = true;
                        state.approvalFeedback.set(approval.approvalId, {phase: "UNKNOWN"});
                        error.message += " 无法确认审批状态，请刷新页面后再操作。";
                    }
                }
            }
            if (decisionAccepted) {
                error.message = `${error.message} 决定已经被服务端接受，请勿重复提交。`;
            }
            throw error;
        } finally {
            state.approvalSubmissions.delete(approval.approvalId);
            streamMessage.article.remove();
            if (isCurrent()) renderChatApprovals();
        }
    }

    // 此操作只查询 GET，不重放决定；查询失败继续锁定，避免把断线误当作服务端未接收。
    async function refreshApprovalFromCard(approval) {
        if (state.approvalSubmissions.has(approval.approvalId)) return;
        const session = sessionEpoch.capture();
        const isCurrent = () => sessionEpoch.isCurrent(session) && state.selectedAgentId === approval.agentId
            && state.selectedConversationId === approval.conversationId;
        if (!isCurrent()) return;
        state.approvalSubmissions.add(approval.approvalId);
        state.approvalFeedback.set(approval.approvalId, {phase: "CHECKING", message: "正在查询服务端权威状态…"});
        renderChatApprovals();
        try {
            await refreshApprovalState(approval, isCurrent);
            if (isCurrent()) {
                state.approvalFeedback.delete(approval.approvalId);
                if (!state.pendingApprovals.has(approval.approvalId)
                        && !state.approvalHistory.some((item) => item.approvalId === approval.approvalId)) {
                    setStatus($("chatFormStatus"), "该审批不存在或已不可访问，已刷新当前会话。", "neutral");
                }
            }
        } catch (error) {
            if (isCurrent()) {
                state.chatLoading = true;
                state.pendingApprovals.set(approval.approvalId, {...approval, canDecide: false});
                state.approvalFeedback.set(approval.approvalId, {phase: "UNKNOWN", message: `${error.message} 未确认结果前不会重新提交决定。`, tone: "error"});
            }
        } finally {
            state.approvalSubmissions.delete(approval.approvalId);
            if (isCurrent()) renderChatApprovals();
        }
    }

    async function refreshApprovalState(approval, isCurrent) {
        if (!isCurrent()) return;
        await loadChatMessages(approval.agentId, approval.conversationId);
        if (!isCurrent()) return;
        try {
            const authoritative = await api.request(`/api/agents/${encodeURIComponent(approval.agentId)}/conversations/${encodeURIComponent(approval.conversationId)}/approvals/${encodeURIComponent(approval.approvalId)}`);
            if (isCurrent()) {
                if (authoritative.status === "PENDING") {
                    state.pendingApprovals.set(approval.approvalId, authoritative);
                } else {
                    state.pendingApprovals.delete(approval.approvalId);
                    state.approvalHistory = core.mergeApprovalHistory(state.approvalHistory, [authoritative], approval.agentId, approval.conversationId);
                }
            }
        } catch (error) {
            if (error.status !== 404) throw error;
            if (isCurrent()) {
                state.pendingApprovals.delete(approval.approvalId);
                state.approvalHistory = state.approvalHistory.filter((item) => item.approvalId !== approval.approvalId);
            }
        }
        if (isCurrent()) { renderChatApprovals(); renderApprovalHistory(); }
    }

    function resetApprovalHistory() {
        approvalHistoryRevision.invalidate();
        state.approvalHistory = [];
        state.approvalHistoryCursor = "";
        state.approvalHistoryBusy = false;
        state.approvalHistoryError = "";
    }

    // 分页失败保留已加载记录和原游标；只在当前会话与加载代次仍一致时更新页面。
    async function loadApprovalHistory(older = false) {
        if (state.approvalHistoryBusy || !state.selectedConversationId || (older && !state.approvalHistoryCursor)) return;
        const agentId = state.selectedAgentId;
        const conversationId = state.selectedConversationId;
        const session = sessionEpoch.capture();
        const revision = approvalHistoryRevision.issue();
        const isCurrent = () => sessionEpoch.isCurrent(session) && approvalHistoryRevision.isCurrent(revision)
            && state.selectedAgentId === agentId && state.selectedConversationId === conversationId;
        const cursor = older ? state.approvalHistoryCursor : "";
        state.approvalHistoryBusy = true;
        state.approvalHistoryError = "";
        renderApprovalHistory();
        try {
            const base = `/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/approvals/history`;
            const page = await api.request(core.buildCursorPath(base, 20, cursor));
            if (!isCurrent()) return;
            state.approvalHistory = core.mergeApprovalHistory(older ? state.approvalHistory : [], page.items || [], agentId, conversationId);
            state.approvalHistoryCursor = page.nextCursor || "";
        } catch (error) {
            if (isCurrent()) state.approvalHistoryError = error.message;
        } finally {
            if (isCurrent()) { state.approvalHistoryBusy = false; renderApprovalHistory(); }
        }
    }

    function renderApprovalHistory() {
        const region = $("chatApprovalHistoryRegion");
        const list = $("chatApprovalHistoryList");
        const messages = $("chatMessageList");
        if (!region || !list || !messages) return;
        region.hidden = !state.selectedConversationId;
        const openedRuns = new Set([...document.querySelectorAll(".chat-approval-history-run[open]")].map((node) => node.dataset.runId));
        messages.querySelectorAll(".chat-approval-history-inline").forEach((node) => node.remove());
        list.replaceChildren();
        const anchors = [...messages.querySelectorAll(".chat-message[data-run-id]")].reverse();
        let unlinked = 0;
        for (const group of core.groupApprovalHistory(state.approvalHistory)) {
            const node = createApprovalHistoryGroup(group);
            node.open = openedRuns.has(group.runId);
            const anchor = anchors.find((message) => message.dataset.runId === group.runId);
            if (anchor) {
                node.classList.add("chat-approval-history-inline");
                anchor.append(node);
            } else {
                unlinked += group.items.length;
                list.append(node);
            }
        }
        $("chatApprovalHistorySummary").textContent = state.approvalHistory.length
            ? `已加载 ${state.approvalHistory.length} 条审批，${state.approvalHistory.length - unlinked} 条位于对应消息下方。${unlinked ? `另有 ${unlinked} 条对应消息不在当前窗口，展示如下。` : ""}`
            : state.approvalHistoryBusy ? "正在读取审批历史…"
                : state.approvalHistoryError ? "审批历史暂时不可用，可重试；这不表示没有历史记录。" : "当前会话暂无已处理审批。";
        const more = $("loadMoreApprovalHistoryBtn");
        if (more) { more.hidden = !state.approvalHistoryCursor; more.disabled = state.approvalHistoryBusy; }
        const refresh = $("refreshApprovalHistoryBtn");
        if (refresh) { refresh.disabled = state.approvalHistoryBusy; refresh.textContent = state.approvalHistoryError ? "重试加载历史" : "刷新记录"; }
        setStatus($("chatApprovalHistoryStatus"), state.approvalHistoryError, "error");
    }

    // 独立只读渲染器不复用决定表单，历史记录即使携带 canDecide=true 也不显示操作控件。
    function createApprovalHistoryGroup(group) {
        const details = element("details", {className: "chat-approval-history-run"});
        details.dataset.runId = group.runId;
        details.append(element("summary", {text: `审批记录 · ${group.items.length} 次确认 · 运行 ${shortIdentifier(group.runId)}`}));
        details.append(element("p", {className: "chat-approval-call", text: `运行编号：${group.runId}。审批允许不等于工具执行成功。`}));
        for (const approval of group.items) {
            const entry = element("article", {className: "chat-approval-history-entry"});
            entry.dataset.approvalId = approval.approvalId;
            entry.append(element("strong", {text: `${core.approvalUiState(approval).label} · ${core.formatDateTime(approval.decidedAt)}`}),
                element("p", {className: "chat-approval-call", text: `发起人：${approval.requestedByDisplayName || "—"} · 审批人：${approval.decidedByDisplayName || "—"} · 审批编号：${approval.approvalId}`}));
            for (const item of approval.items || []) {
                const call = element("details", {className: "chat-approval-history-item"});
                const decision = item.decision === "APPROVE" ? "已允许" : item.decision === "DENY" ? "已拒绝" : "未作决定";
                call.append(element("summary", {text: `${item.toolName || "未命名工具"} · ${decision}`}),
                    element("p", {className: "chat-approval-call", text: `调用标识：${item.toolCallId || "—"} · 调用参数（已脱敏）`}),
                    element("pre", {className: "chat-approval-summary", text: item.inputSummary || "未提供可展示参数。"}));
                entry.append(call);
            }
            details.append(entry);
        }
        return details;
    }

    function streamEventError(data, fallback) {
        const code = data?.code ? `（错误码：${data.code}）` : "";
        const errorId = data?.errorId ? `（错误编号：${data.errorId}）` : "";
        return new Error(`${data?.message || fallback}${code}${errorId}`);
    }

    function shortIdentifier(value) {
        const text = String(value || "—");
        return text.length > 8 ? text.slice(0, 8) : text;
    }

    function renderChatMessages(messages) {
        const container = $("chatMessageList");
        if (!container) return;
        container.replaceChildren();
        const conversation = state.conversations.find((item) => item.id === state.selectedConversationId);
        $("chatConversationTitle").textContent = conversation?.title || "当前会话";
        if (!messages.length) {
            renderChatWelcome("当前会话还没有消息，输入问题开始聊天。", false);
            return;
        }
        messages.forEach((message) => container.append(createChatMessage(message)));
        scrollChatToBottom();
    }

    function renderChatWelcome(message, replace = true) {
        const container = $("chatMessageList");
        if (!container) return;
        if (replace) container.replaceChildren();
        $("chatConversationTitle").textContent = state.selectedConversationId ? "当前会话" : "请选择会话";
        const welcome = element("div", {className: "chat-welcome"});
        welcome.append(
            element("span", {className: "runs-detail-mark", text: "◌"}),
            element("h3", {text: "开始一段会话"}),
            element("p", {text: message})
        );
        container.append(welcome);
    }

    function createChatMessage(message) {
        const isUser = message.role === "USER";
        const article = element("article", {className: `chat-message ${isUser ? "chat-message-user" : "chat-message-agent"}`});
        if (message.runId) article.dataset.runId = message.runId;
        const label = isUser ? "你" : (message.senderName || "Agent");
        article.append(element("p", {className: "chat-message-label", text: label}));
        const bubble = element("div", {className: "chat-message-bubble"});
        const blocks = Array.isArray(message.contentBlocks) ? message.contentBlocks : [];
        appendChatBlocks(bubble, blocks);
        if (!blocks.length) bubble.append(element("p", {text: "消息内容为空。"}));
        article.append(bubble);
        return article;
    }

    function appendChatBlocks(container, blocks) {
        let traceBlocks = [];
        const flushTrace = () => {
            if (!traceBlocks.length) return;
            container.append(createChatExecutionTrace(traceBlocks));
            traceBlocks = [];
        };
        blocks.forEach((block) => {
            if (block.type === "TEXT") {
                flushTrace();
                const output = element("div", {className: "markdown-output"});
                renderMarkdown(output, String(block.text || ""));
                container.append(output);
            } else if (["THINKING", "TOOL_USE", "TOOL_RESULT"].includes(block.type)) {
                traceBlocks.push(block);
            }
        });
        flushTrace();
    }

    function createChatExecutionTrace(blocks) {
        const trace = element("details", {className: "chat-execution-trace"});
        const grouped = groupChatExecutionBlocks(blocks);
        const summary = element("summary", {
            text: `执行过程 · ${grouped.length} 个步骤 · 已完成`
        });
        const body = element("div", {className: "chat-execution-body"});
        grouped.forEach((step) => body.append(renderChatExecutionStep(step)));
        trace.append(summary, body);
        return trace;
    }

    function groupChatExecutionBlocks(blocks) {
        const steps = [];
        const tools = new Map();
        blocks.forEach((block, index) => {
            if (block.type === "THINKING") {
                steps.push({kind: "thinking", order: index, content: String(block.text || "")});
                return;
            }
            const callId = String(block.toolCallId || `tool-${index}`);
            let step = tools.get(callId);
            if (!step) {
                step = {
                    kind: "tool", order: index, callId, toolName: "未命名 Tool",
                    input: "", output: "", status: "", durationMillis: null
                };
                tools.set(callId, step);
                steps.push(step);
            }
            if (block.type === "TOOL_USE") {
                step.toolName = block.toolName || step.toolName;
                step.input = String(block.text || "");
            } else if (block.type === "TOOL_RESULT") {
                step.output = String(block.text || "");
                step.status = block.status || step.status;
                step.durationMillis = block.durationMillis ?? step.durationMillis;
            }
        });
        return steps.sort((left, right) => left.order - right.order);
    }

    function renderChatExecutionStep(step) {
        const section = element("section", {className: `chat-execution-step chat-execution-${step.kind}`});
        const heading = element("div", {className: "chat-execution-step-heading"});
        if (step.kind === "thinking") {
            heading.append(
                element("strong", {text: "思考过程"}),
                element("span", {text: "模型提供 · 已完成"})
            );
            const content = element("div", {className: "chat-thinking-content markdown-output"});
            renderMarkdown(content, step.content);
            section.append(heading, content);
            return section;
        }
        heading.append(
            element("strong", {text: step.toolName}),
            element("span", {text: chatToolStatusText(step.status, step.durationMillis)})
        );
        const payloads = element("div", {className: "chat-tool-payloads"});
        payloads.append(
            renderToolCallPayload("调用入参（已脱敏）", step.input, "未记录调用入参。"),
            renderToolCallPayload("返回值（已脱敏）", step.output, "未记录返回值或错误信息。")
        );
        section.dataset.status = String(step.status || "UNKNOWN");
        section.append(heading, payloads);
        return section;
    }

    function chatToolStatusText(status, durationMillis = null) {
        const labels = {
            RUNNING: "执行中",
            WAITING_APPROVAL: "等待审批",
            SUCCEEDED: "执行成功",
            FAILED: "执行失败",
            DENIED: "已拒绝"
        };
        const label = labels[status] || "已完成";
        return durationMillis === null || durationMillis === undefined ? label : `${label} · ${durationMillis} ms`;
    }

    function appendChatStreamingMessage() {
        const container = $("chatMessageList");
        const article = element("article", {className: "chat-message chat-message-agent is-streaming"});
        article.append(element("p", {className: "chat-message-label", text: "Agent · 正在生成"}));
        const bubble = element("div", {className: "chat-message-bubble"});
        const trace = element("details", {className: "chat-execution-trace is-live"});
        trace.open = true;
        trace.hidden = true;
        const traceSummary = element("summary", {text: "执行过程 · 正在运行"});
        const traceBody = element("div", {className: "chat-execution-body"});
        trace.append(traceSummary, traceBody);
        const output = element("div", {className: "markdown-output"});
        output.id = "chatStreamOutput";
        bubble.append(trace, output);
        article.append(bubble);
        container.append(article);
        return {article, output, trace, traceSummary, traceBody, steps: new Map()};
    }

    function renderChatProgress(streamMessage, progress) {
        if (!streamMessage || !progress?.type) return;
        streamMessage.trace.hidden = false;
        const isThinking = progress.type.startsWith("THINKING_");
        const key = isThinking
            ? `thinking:${progress.blockId || progress.replyId || "current"}`
            : `tool:${progress.toolCallId || progress.toolName || "current"}`;
        let step = streamMessage.steps.get(key);
        if (!step) {
            const section = element("section", {
                className: `chat-execution-step chat-execution-${isThinking ? "thinking" : "tool"}`
            });
            const heading = element("div", {className: "chat-execution-step-heading"});
            const title = element("strong", {text: isThinking ? "思考过程" : (progress.toolName || "Tool")});
            const status = element("span", {text: isThinking ? "正在思考" : "准备调用"});
            heading.append(title, status);
            const content = element("div", {
                className: isThinking ? "chat-thinking-content markdown-output" : "chat-tool-live-content",
                text: isThinking ? "模型正在生成思考内容…" : "等待工具参数准备完成。"
            });
            section.append(heading, content);
            step = {section, title, status, content, isThinking, input: "", output: "", durationMillis: null};
            streamMessage.steps.set(key, step);
            streamMessage.traceBody.append(section);
        }
        if (!isThinking && progress.toolName) step.title.textContent = progress.toolName;
        if (progress.type === "THINKING_STARTED") {
            step.status.textContent = "正在思考";
        } else if (progress.type === "THINKING_COMPLETED") {
            step.status.textContent = "模型提供 · 已完成";
            renderMarkdown(step.content, String(progress.content || ""));
        } else if (progress.type === "TOOL_CALL_STARTED") {
            step.status.textContent = "参数已准备";
            step.input = String(progress.input || "");
            renderChatLiveToolPayload(step);
        } else if (progress.type === "TOOL_CALL_COMPLETED") {
            step.status.textContent = "参数已准备";
            if (!step.input) step.content.textContent = "正在整理工具调用入参。";
        } else if (progress.type === "TOOL_EXECUTION_STARTED") {
            step.status.textContent = "执行中";
            if (!step.input) step.content.textContent = "已进入受治理工具执行阶段。";
            step.section.dataset.status = "RUNNING";
        } else if (progress.type === "TOOL_EXECUTION_COMPLETED") {
            step.output = String(progress.output || "");
            step.durationMillis = progress.durationMillis ?? null;
            step.status.textContent = chatToolStatusText(progress.status, step.durationMillis);
            renderChatLiveToolPayload(step);
            step.section.dataset.status = String(progress.status || "UNKNOWN");
        }
        streamMessage.traceSummary.textContent = `执行过程 · ${streamMessage.steps.size} 个步骤 · 正在运行`;
        scrollChatToBottom();
    }

    function renderChatLiveToolPayload(step) {
        const payloads = element("div", {className: "chat-tool-payloads"});
        payloads.append(
            renderToolCallPayload("调用入参（已脱敏）", step.input, "尚未收到调用入参。"),
            renderToolCallPayload("返回值（已脱敏）", step.output, "等待工具返回。")
        );
        step.content.replaceChildren(payloads);
    }

    function scrollChatToBottom() {
        const container = $("chatMessageList");
        if (container) container.scrollTop = container.scrollHeight;
    }

    function setChatBusy(busy) {
        state.chatBusy = busy;
        [$("chatAgentSelect"), $("newChatConversationBtn"), $("chatSendBtn")].filter(Boolean)
            .forEach((control) => { control.disabled = busy; });
        updateChatSendAvailability();
    }

    function updateChatSendAvailability() {
        const waiting = [...state.pendingApprovals.values()]
            .some((approval) => approval.status === "PENDING");
        const sendButton = $("chatSendBtn");
        const input = $("chatInput");
        const resuming = [...state.approvalSubmissions].some((id) => state.pendingApprovals.has(id)
            || state.approvalHistory.some((approval) => approval.approvalId === id));
        if (sendButton) sendButton.disabled = state.chatBusy || state.chatLoading || waiting || resuming;
        if (input) input.disabled = state.chatBusy || state.chatLoading || waiting || resuming;
        const liveStatus = $("chatLiveStatus");
        if (liveStatus) {
            const activePhase = [...state.pendingApprovals.values(), ...state.approvalHistory]
                .filter((approval) => state.approvalSubmissions.has(approval.approvalId))
                .map((approval) => state.approvalFeedback.get(approval.approvalId)?.phase);
            liveStatus.textContent = state.chatBusy ? "正在生成" : activePhase.includes("RESUMING") ? "正在恢复运行"
                : activePhase.includes("CHECKING") ? "正在核对审批状态" : resuming ? "正在提交审批"
                    : state.chatLoading ? "正在确认会话状态" : waiting ? "请先处理待审批操作" : "已就绪";
            liveStatus.classList.toggle("is-streaming", state.chatBusy);
        }
    }

    async function sendChatMessage() {
        if (state.chatBusy || state.chatLoading || [...state.pendingApprovals.values()]
                .some((approval) => approval.status === "PENDING" || state.approvalSubmissions.has(approval.approvalId))
                || state.approvalHistory.some((approval) => state.approvalSubmissions.has(approval.approvalId))) return;
        const agentId = $("chatAgentSelect")?.value;
        const input = $("chatInput")?.value.trim();
        if (!agentId || !input) {
            setStatus($("chatFormStatus"), "请选择 Agent 并输入消息。", "error");
            return;
        }
        let streamedOutput = "";
        try {
            setChatBusy(true);
            await withSubmitState($("chatSendBtn"), async () => {
                state.selectedAgentId = agentId;
                const conversationId = await ensureConversation(agentId);
                const container = $("chatMessageList");
                container.querySelector(".chat-welcome")?.remove();
                container.append(createChatMessage({role: "USER", contentBlocks: [{type: "TEXT", text: input}]}));
                const streamMessage = appendChatStreamingMessage();
                scrollChatToBottom();
                $("chatInput").value = "";
                setStatus($("chatFormStatus"), "正在接收回答…", "neutral");
                let result = null;
                let approvalRequired = null;
                await api.stream(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/messages/stream`, {
                    method: "POST",
                    body: JSON.stringify({input})
                }, (event) => {
                    if (event.type === "delta") {
                        const delta = String(event.data?.delta || "");
                        if (!delta) return;
                        streamedOutput += delta;
                        renderMarkdown(streamMessage.output, streamedOutput);
                        scrollChatToBottom();
                    } else if (event.type === "progress") {
                        renderChatProgress(streamMessage, event.data);
                    } else if (event.type === "completed") {
                        result = event.data;
                    } else if (event.type === "approval-required") {
                        approvalRequired = event.data;
                    } else if (event.type === "error") {
                        throw streamEventError(event.data, "会话运行失败");
                    }
                });
                if (!result && !approvalRequired) throw new Error("运行未返回最终结果，请刷新会话确认状态。");
                await loadConversations(agentId);
                await loadChatMessages(agentId, conversationId);
                setStatus($("chatFormStatus"), approvalRequired
                    ? "运行已暂停，请处理下方高风险工具审批。"
                    : "回答已完成。", approvalRequired ? "neutral" : "success");
            });
        } catch (error) {
            setStatus($("chatFormStatus"), error.message, "error");
            if (state.selectedConversationId) {
                loadChatMessages(agentId, state.selectedConversationId).catch(() => {});
            }
        } finally {
            setChatBusy(false);
        }
    }

    let streamingMarkdownOutput = "";

    function renderStreamingRunDetail(input) {
        const container = $("runDetail");
        if (!container) return;
        streamingMarkdownOutput = "";
        container.classList.add("is-streaming");
        const heading = element("div", {className: "panel-heading run-streaming-heading"});
        const titleGroup = element("div");
        titleGroup.append(
            element("p", {className: "eyebrow", text: "流式运行"}),
            element("h2", {text: "正在生成回答"})
        );
        heading.append(titleGroup, statusBadge("RUNNING"));
        const inputSection = element("section", {className: "detail-section run-input-section"});
        inputSection.append(element("h3", {text: "本次输入"}), element("p", {className: "run-copy-block", text: input}));
        const outputSection = element("section", {className: "detail-section stream-output-section"});
        const outputHeading = element("div", {className: "stream-output-heading"});
        outputHeading.append(element("h3", {text: "实时输出"}), element("span", {className: "stream-output-state", text: "正在接收 · Markdown"}));
        const output = element("div", {className: "markdown-output"});
        output.id = "streamOutput";
        output.setAttribute("aria-live", "polite");
        outputSection.append(outputHeading, output);
        container.replaceChildren(heading, inputSection, outputSection);
    }

    function appendStreamingOutput(delta) {
        const output = $("streamOutput");
        if (!output) return;
        streamingMarkdownOutput += delta;
        renderMarkdown(output, streamingMarkdownOutput);
        output.scrollTop = output.scrollHeight;
    }

    function setStreamingOutput(value) {
        const output = $("streamOutput");
        if (!output) return;
        streamingMarkdownOutput = value;
        renderMarkdown(output, streamingMarkdownOutput);
        output.scrollTop = output.scrollHeight;
    }

    /**
     * 将模型输出以受限 Markdown 转换为 DOM 节点。
     *
     * <p>模型文本始终通过 {@code textContent} 或文本节点写入，绝不将模型输出交给
     * {@code innerHTML} 解析；链接协议也会白名单校验，以避免运行结果突破控制台安全边界。</p>
     *
     * @param container 接收渲染结果的容器
     * @param source 模型返回的 Markdown 原文
     */
    function renderMarkdown(container, source) {
        container.replaceChildren(markdownFragment(String(source || "")));
    }

    function markdownFragment(source) {
        const fragment = document.createDocumentFragment();
        const lines = source.replace(/\r\n?/g, "\n").split("\n");
        let index = 0;
        while (index < lines.length) {
            const line = lines[index];
            if (!line.trim()) {
                index += 1;
                continue;
            }
            const fence = line.match(/^\s*```([^`]*)$/);
            if (fence) {
                const codeLines = [];
                index += 1;
                while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) {
                    codeLines.push(lines[index]);
                    index += 1;
                }
                if (index < lines.length) index += 1;
                const pre = element("pre", {className: "markdown-code-block"});
                const code = element("code", {text: codeLines.join("\n")});
                const language = fence[1].trim().toLowerCase();
                if (/^[a-z0-9+#.-]{1,32}$/.test(language)) code.className = `language-${language}`;
                pre.append(code);
                fragment.append(pre);
                continue;
            }
            const heading = line.match(/^(#{1,6})\s+(.+)$/);
            if (heading) {
                const node = element(`h${heading[1].length}`);
                appendInlineMarkdown(node, heading[2]);
                fragment.append(node);
                index += 1;
                continue;
            }
            if (/^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
                fragment.append(element("hr"));
                index += 1;
                continue;
            }
            if (isMarkdownTable(lines, index)) {
                const headers = markdownTableCells(lines[index]);
                const wrapper = element("div", {className: "markdown-table-scroll"});
                const table = element("table");
                const head = element("thead");
                const row = element("tr");
                headers.forEach((value) => {
                    const cell = element("th");
                    appendInlineMarkdown(cell, value);
                    row.append(cell);
                });
                head.append(row);
                table.append(head);
                const body = element("tbody");
                index += 2;
                while (index < lines.length && lines[index].includes("|") && lines[index].trim()) {
                    const cells = markdownTableCells(lines[index]);
                    const bodyRow = element("tr");
                    headers.forEach((ignored, cellIndex) => {
                        const cell = element("td");
                        appendInlineMarkdown(cell, cells[cellIndex] || "");
                        bodyRow.append(cell);
                    });
                    body.append(bodyRow);
                    index += 1;
                }
                table.append(body);
                wrapper.append(table);
                fragment.append(wrapper);
                continue;
            }
            const unordered = line.match(/^\s*[-+*]\s+(.+)$/);
            const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
            if (unordered || ordered) {
                const list = element(unordered ? "ul" : "ol");
                const matcher = unordered ? /^\s*[-+*]\s+(.+)$/ : /^\s*\d+[.)]\s+(.+)$/;
                while (index < lines.length) {
                    const item = lines[index].match(matcher);
                    if (!item) break;
                    const listItem = element("li");
                    appendInlineMarkdown(listItem, item[1]);
                    list.append(listItem);
                    index += 1;
                }
                fragment.append(list);
                continue;
            }
            if (/^>\s?/.test(line)) {
                const quoteLines = [];
                while (index < lines.length && /^>\s?/.test(lines[index])) {
                    quoteLines.push(lines[index].replace(/^>\s?/, ""));
                    index += 1;
                }
                const quote = element("blockquote");
                quote.append(markdownFragment(quoteLines.join("\n")));
                fragment.append(quote);
                continue;
            }
            const paragraphLines = [];
            while (index < lines.length && lines[index].trim() && !isMarkdownBlockStart(lines, index)) {
                paragraphLines.push(lines[index]);
                index += 1;
            }
            if (!paragraphLines.length) {
                paragraphLines.push(lines[index]);
                index += 1;
            }
            const paragraph = element("p");
            appendMarkdownParagraph(paragraph, paragraphLines);
            fragment.append(paragraph);
        }
        return fragment;
    }

    function isMarkdownBlockStart(lines, index) {
        const line = lines[index] || "";
        return /^\s*```/.test(line)
                || /^(#{1,6})\s+/.test(line)
                || /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/.test(line)
                || /^\s*[-+*]\s+/.test(line)
                || /^\s*\d+[.)]\s+/.test(line)
                || /^>\s?/.test(line)
                || isMarkdownTable(lines, index);
    }

    function isMarkdownTable(lines, index) {
        if (!lines[index]?.includes("|") || !lines[index + 1]) return false;
        const separators = markdownTableCells(lines[index + 1]);
        return separators.length > 0 && separators.every((value) => /^:?-{3,}:?$/.test(value));
    }

    function markdownTableCells(line) {
        return line.trim().replace(/^\||\|$/g, "").split("|").map((value) => value.trim());
    }

    function appendMarkdownParagraph(container, lines) {
        lines.forEach((line, index) => {
            const hardBreak = / {2}$/.test(line);
            appendInlineMarkdown(container, hardBreak ? line.slice(0, -2) : line);
            if (index < lines.length - 1) container.append(hardBreak ? element("br") : document.createTextNode(" "));
        });
    }

    function appendInlineMarkdown(container, source) {
        const tokenPattern = /(`[^`]+`|\[[^\]]+\]\([^)]+\)|\*\*[^*]+\*\*|__[^_]+__|~~[^~]+~~|\*[^*\n]+\*|_[^_\n]+_)/g;
        let cursor = 0;
        for (const match of source.matchAll(tokenPattern)) {
            if (match.index > cursor) container.append(document.createTextNode(source.slice(cursor, match.index)));
            appendMarkdownToken(container, match[0]);
            cursor = match.index + match[0].length;
        }
        if (cursor < source.length) container.append(document.createTextNode(source.slice(cursor)));
    }

    function appendMarkdownToken(container, token) {
        if (token.startsWith("`")) {
            container.append(element("code", {text: token.slice(1, -1)}));
            return;
        }
        const link = token.match(/^\[([^\]]+)]\(([^)]+)\)$/);
        if (link) {
            const href = safeMarkdownLink(link[2]);
            if (!href) {
                container.append(document.createTextNode(token));
                return;
            }
            const anchor = element("a");
            anchor.href = href;
            if (!href.startsWith("#")) {
                anchor.target = "_blank";
                anchor.rel = "noreferrer noopener";
            }
            appendInlineMarkdown(anchor, link[1]);
            container.append(anchor);
            return;
        }
        const marker = token.startsWith("**") || token.startsWith("__") ? 2 : token.startsWith("~~") ? 2 : 1;
        const tagName = token.startsWith("**") || token.startsWith("__") ? "strong"
                : token.startsWith("~~") ? "del" : "em";
        const node = element(tagName);
        appendInlineMarkdown(node, token.slice(marker, -marker));
        container.append(node);
    }

    function safeMarkdownLink(value) {
        const raw = String(value || "").trim();
        if (raw.startsWith("#")) return raw;
        try {
            const url = new URL(raw, window.location.origin);
            return ["http:", "https:", "mailto:"].includes(url.protocol) ? url.href : "";
        } catch {
            return "";
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
        container.classList.remove("is-streaming");
        const heading = element("div", {className: "panel-heading"});
        const titleGroup = element("div");
        titleGroup.append(element("p", {className: "eyebrow", text: "运行详情"}));
        titleGroup.append(element("h2", {text: `运行 ${run.id || "—"}`}));
        heading.append(titleGroup, statusBadge(run.status));
        const inputSection = element("section", {className: "detail-section run-input-section"});
        inputSection.append(element("h3", {text: "本次输入"}), element("p", {className: "run-copy-block", text: run.input || "无输入"}));
        const outputSection = element("section", {className: "detail-section stream-output-section"});
        outputSection.append(element("h3", {text: "最终输出 · Markdown"}));
        const output = element("div", {className: "markdown-output"});
        renderMarkdown(output, run.output || "暂无输出");
        outputSection.append(output);
        const dl = definitionList([
            ["错误", run.errorMessage],
            ["执行主体", run.principalId],
            ["开始时间", core.formatDateTime(run.startedAt)],
            ["结束时间", core.formatDateTime(run.finishedAt)]
        ]);
        const metadataSection = element("section", {className: "detail-section run-metadata-section"});
        metadataSection.append(element("h3", {text: "运行信息"}), dl);
        const callsSection = element("section", {className: "detail-section run-tool-calls-section"});
        callsSection.id = "runToolCalls";
        callsSection.append(element("h3", {text: "工具调用"}));
        const toolCalls = Array.isArray(detail?.toolCalls) ? detail.toolCalls : [];
        if (!toolCalls.length) {
            callsSection.append(emptyState("本次运行未产生工具调用。"));
        } else {
            toolCalls.forEach((call) => callsSection.append(renderToolCall(call)));
        }
        container.replaceChildren(heading, inputSection, outputSection, metadataSection, callsSection);
    }

    function renderToolCall(call) {
        const article = element("article", {className: "tool-call"});
        const heading = element("div", {className: "tool-call-heading"});
        const titleGroup = element("div");
        titleGroup.append(
                element("p", {className: "eyebrow", text: "Tool 调用"}),
                element("h4", {text: call.toolName || "未命名 Tool"})
        );
        heading.append(titleGroup, statusBadge(call.status));
        const meta = element("div", {className: "tool-call-meta"});
        meta.append(
                toolCallMetaItem("授权状态", call.authorized ? "已授权" : "未授权"),
                toolCallMetaItem(
                        "执行耗时",
                        call.durationMillis === null || call.durationMillis === undefined ? "—" : `${call.durationMillis} ms`
                ),
                toolCallMetaItem("内容范围", "已脱敏摘要")
        );
        const payloads = element("div", {className: "tool-call-payloads"});
        payloads.append(
                renderToolCallPayload("请求输入", call.inputSummary, "未记录输入摘要。"),
                renderToolCallPayload("执行输出", call.outputSummary, "未返回输出摘要。")
        );
        if (String(call.errorMessage || "").trim()) {
            const error = renderToolCallPayload("错误信息", call.errorMessage, "");
            error.classList.add("tool-call-error-payload");
            payloads.append(error);
        }
        article.append(heading, meta, payloads);
        return article;
    }

    function toolCallMetaItem(label, value) {
        const item = element("div", {className: "tool-call-meta-item"});
        item.append(element("span", {text: label}), element("strong", {text: value}));
        return item;
    }

    function renderToolCallPayload(title, value, emptyMessage) {
        const section = element("section", {className: "tool-call-payload"});
        const formatted = formatToolCallPayload(value, emptyMessage);
        const heading = element("div", {className: "tool-call-payload-heading"});
        heading.append(element("h5", {text: title}), element("span", {text: formatted.format}));
        section.append(heading, element("pre", {className: "tool-call-payload-content", text: formatted.content}));
        return section;
    }

    function formatToolCallPayload(value, emptyMessage) {
        const content = String(value || "").trim();
        if (!content) return {content: emptyMessage, format: "无内容"};
        try {
            return {content: JSON.stringify(JSON.parse(content), null, 2), format: "JSON"};
        } catch {
            return {content, format: "文本"};
        }
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
        bind("cancelAgentEditBtn", "click", resetAgentForm);
        bind("modelConfigForm", "submit", (event) => { event.preventDefault(); submitModelConfig(); });
        bind("cancelModelConfigEditBtn", "click", resetModelConfigForm);
        bind("discoverModelNamesBtn", "click", discoverModelCatalog);
        bind("modelConfigProviderType", "change", updateDiscoverModelCatalogButton);
        bind("modelConfigBaseUrl", "input", updateDiscoverModelCatalogButton);
        bind("modelConfigApiKey", "input", updateDiscoverModelCatalogButton);
        bind("toolForm", "submit", (event) => { event.preventDefault(); submitTool(); });
        bind("cancelToolEditBtn", "click", () => resetToolForm());
        bind("fillHttpExampleBtn", "click", fillHttpToolExample);
        bind("addHttpParameterBtn", "click", () => addHttpParameter());
        bind("grantForm", "submit", (event) => { event.preventDefault(); grantTool(); });
        bind("debugToolForm", "submit", (event) => { event.preventDefault(); debugTool(); });
        bind("runForm", "submit", (event) => { event.preventDefault(); runAgent(); });
        bind("toolType", "change", toggleHttpConfigFields);
        bind("refreshAgentsBtn", "click", () => loadAgents().catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("refreshModelConfigsBtn", "click", () => loadModelConfigs().catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("refreshToolsBtn", "click", () => loadTools().catch((error) => setStatus($("globalStatus"), error.message, "error")));
        bind("refreshRunsBtn", "click", () => loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("loadMoreRunsBtn", "click", () => loadRuns({append: true}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("runAgentSelect", "change", () => {
            const agentId = $("runAgentSelect").value;
            state.selectedConversationId = "";
            Promise.all([loadRuns({append: false}), loadConversations(agentId)])
                .catch((error) => setStatus($("runFormStatus"), error.message, "error"));
        });
        bind("newConversationBtn", "click", () => createConversation()
            .catch((error) => setStatus($("runFormStatus"), error.message, "error")));
        bind("runConversationSelect", "change", () => {
            state.selectedConversationId = $("runConversationSelect").value;
            loadConversationMessages($("runAgentSelect").value, state.selectedConversationId)
                .catch((error) => setStatus($("runFormStatus"), error.message, "error"));
        });
        bind("chatForm", "submit", (event) => { event.preventDefault(); sendChatMessage(); });
        bind("loadMoreApprovalHistoryBtn", "click", () => loadApprovalHistory(true));
        bind("refreshApprovalHistoryBtn", "click", () => loadApprovalHistory(false));
        bind("chatAgentSelect", "change", () => {
            const agentId = $("chatAgentSelect").value;
            state.selectedAgentId = agentId;
            state.selectedConversationId = "";
            state.pendingApprovals.clear();
            approvalDrafts.clear();
            resetApprovalHistory();
            renderApprovalHistory();
            state.chatLoading = false;
            chatLoadRevision.invalidate();
            renderChatApprovals();
            loadConversations(agentId)
                .then(() => state.selectedConversationId
                    ? loadChatMessages(agentId, state.selectedConversationId)
                    : renderChatWelcome("当前 Agent 还没有会话，发送第一条消息即可开始。"))
                .catch((error) => setStatus($("chatFormStatus"), error.message, "error"));
        });
        bind("newChatConversationBtn", "click", () => createChatConversation()
            .catch((error) => setStatus($("chatFormStatus"), error.message, "error")));
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
