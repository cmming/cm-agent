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
        selectedToolId: "",
        runs: [],
        runCursor: "",
        selectedRunId: "",
        auditEvents: [],
        auditCursor: ""
    };
    const toolPublicationLock = core.createToolPublicationLock();
    const toolLoadRevision = core.createLoadRevisionGate();

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
        updateAgentOptions();
        renderRuns();
        renderAudit();
        updateOverview();
        resetSessionViews();
        setStatus($("globalStatus"));
        setStatus($("loginStatus"), message, "neutral");
    }

    function resetSessionViews() {
        $("runInput").value = "";
        $("debugInput").value = "{}";
        $("debugConfirmedToolName").value = "";
        renderMessage($("runDetail"), "选择一条运行记录查看详情。");
        renderMessage($("debugResult"), "调试结果将显示在这里。");
        ["agentFormStatus", "toolFormStatus", "grantFormStatus", "runFormStatus", "debugFormStatus"].forEach((id) => setStatus($(id)));
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
        if (pageId === "runsPage" && state.selectedAgentId && !state.runs.length) {
            loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error"));
        }
        if (pageId === "auditPage" && !state.auditEvents.length) {
            loadAudit({append: false}).catch((error) => setStatus($("globalStatus"), error.message, "error"));
        }
    }

    async function loadInitialData() {
        setStatus($("globalStatus"), "正在加载当前租户资源…");
        try {
            await Promise.all([loadAgents(), loadTools()]);
            if (state.selectedAgentId) await loadRuns({append: false});
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

    async function loadTools(revision = toolLoadRevision.issue()) {
        const tools = await api.request("/api/tools");
        if (!toolLoadRevision.isCurrent(revision)) {
            return false;
        }
        state.tools = Array.isArray(tools) ? tools : [];
        if (!state.tools.some((tool) => tool.id === state.selectedToolId)) {
            state.selectedToolId = state.tools[0]?.id || "";
        }
        renderTools();
        updateToolOptions();
        updateDebugToolOptions();
        updateOverview();
        return true;
    }

    function renderTools() {
        const container = $("toolList");
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
            item.addEventListener("click", () => {
                state.selectedToolId = tool.id;
                renderTools();
                $("grantToolSelect").value = tool.id;
                $("debugToolSelect").value = tool.id;
            });
            card.append(item);
            if (tool.type === "HTTP") {
                const actions = element("div", {className: "tool-actions"});
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
                card.append(actions);
            }
            container.append(card);
        });
    }

    async function createTool() {
        const fields = {
            name: $("toolName").value.trim(),
            description: $("toolDescription").value.trim(),
            type: $("toolType").value,
            riskLevel: $("toolRiskLevel").value
        };
        let payload;
        try {
            payload = fields.type === "HTTP"
                ? core.buildHttpToolPayload({
                    ...fields,
                    mcpPublished: $("toolMcpPublished").checked,
                    method: $("httpMethod").value,
                    urlTemplate: $("httpUrlTemplate").value,
                    inputSchemaText: $("httpInputSchema").value,
                    parameterMappingsText: $("httpParameterMappings").value,
                    secretHeadersText: $("httpSecretHeaders").value,
                    timeoutMillis: $("httpTimeoutMillis").value
                })
                : fields;
        } catch (error) {
            setStatus($("toolFormStatus"), error.message, "error");
            return;
        }
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

    function updateDebugToolOptions() {
        const select = $("debugToolSelect");
        const debugTools = state.tools.filter((tool) => tool.type === "HTTP" || tool.type === "LOCAL");
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
        const isHttp = $("toolType").value === "HTTP";
        $("httpConfigFields").hidden = !isHttp;
        ["httpUrlTemplate", "httpInputSchema", "httpParameterMappings", "httpSecretHeaders", "httpTimeoutMillis"].forEach((id) => {
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
            setStatus($("debugFormStatus"), tool.riskLevel === "HIGH"
                ? "HIGH 风险 Tool 的确认名称必须与 Tool 名称完全一致。" : "该 Tool 类型不支持调试。", "error");
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
                setStatus($("debugFormStatus"), result?.success ? "调试完成。" : "调试未成功完成。", result?.success ? "success" : "error");
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
            ["错误", result?.errorMessage]
        ]));
    }

    function updateOverview() {
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
        const agentId = $("runAgentSelect").value || state.selectedAgentId;
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
            renderMessage($("runDetail"), "选择一条运行记录查看详情。");
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
        const agentId = $("runAgentSelect").value || state.selectedAgentId;
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

    $("loginForm").addEventListener("submit", (event) => {
        event.preventDefault();
        login();
    });
    $("logoutBtn").addEventListener("click", () => logout());
    $("agentForm").addEventListener("submit", (event) => { event.preventDefault(); createAgent(); });
    $("toolForm").addEventListener("submit", (event) => { event.preventDefault(); createTool(); });
    $("grantForm").addEventListener("submit", (event) => { event.preventDefault(); grantTool(); });
    $("debugToolForm").addEventListener("submit", (event) => { event.preventDefault(); debugTool(); });
    $("runForm").addEventListener("submit", (event) => { event.preventDefault(); runAgent(); });
    $("toolType").addEventListener("change", toggleHttpConfigFields);
    $("refreshAgentsBtn").addEventListener("click", () => loadAgents().catch((error) => setStatus($("globalStatus"), error.message, "error")));
    $("refreshToolsBtn").addEventListener("click", () => loadTools().catch((error) => setStatus($("globalStatus"), error.message, "error")));
    $("refreshRunsBtn").addEventListener("click", () => loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
    $("loadMoreRunsBtn").addEventListener("click", () => loadRuns({append: true}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
    $("runAgentSelect").addEventListener("change", () => loadRuns({append: false}).catch((error) => setStatus($("runFormStatus"), error.message, "error")));
    $("refreshAuditBtn").addEventListener("click", () => loadAudit({append: false}).catch((error) => setStatus($("globalStatus"), error.message, "error")));
    $("loadMoreAuditBtn").addEventListener("click", () => loadAudit({append: true}).catch((error) => setStatus($("globalStatus"), error.message, "error")));
    document.querySelectorAll("[data-page]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.page)));
    document.querySelectorAll("[data-navigate]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.navigate)));

    toggleHttpConfigFields();
    logout("请输入用户名和密码。");
})();
