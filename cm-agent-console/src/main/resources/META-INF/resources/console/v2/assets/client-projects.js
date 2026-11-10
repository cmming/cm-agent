(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentClientProjects = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";
    const boundary = {"X-CM-Agent-Client-Project": "1"};

    function createRequestId(crypto) {
        if (typeof crypto?.randomUUID === "function") return crypto.randomUUID();
        if (typeof crypto?.getRandomValues !== "function")
            throw new Error("当前浏览器无法生成安全的请求编号，请使用 HTTPS 或更新浏览器。");
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    async function waitForTask({request, task, statusPath, host = globalThis, valid = () => true}) {
        if (!task?.requestId || !task?.expiresAt) throw new Error("设备任务句柄不完整。");
        const deadline = Date.parse(task.expiresAt);
        if (!Number.isFinite(deadline)) throw new Error("设备任务期限无法识别。");
        for (let attempt = 0; attempt < 360; attempt += 1) {
            if (!valid()) throw new Error("项目工作区已离开，请返回后核对原任务。");
            const result = await request(statusPath(task.requestId));
            if (result?.requestId !== task.requestId) throw new Error("设备任务状态不匹配。");
            if (result.state === "SUCCEEDED") return result;
            if (result.state === "FAILED" || result.state === "UNKNOWN" || result.state === "CANCELLED")
                throw new Error(result.error || "设备任务未完成，请核对原请求。");
            if (Date.now() >= deadline) throw new Error("设备任务等待超时，请核对原请求。");
            await new Promise(resolve => host.setTimeout(resolve, 500));
        }
        throw new Error("设备任务等待超过页面预算，请刷新后核对原请求。");
    }

    function canApproveChange(reference, preview, development) {
        return !!reference && preview?.displayExact === true
            && development?.matchesPreview?.(reference, preview) === true
            && development?.completePreview?.(preview, false) === true;
    }

    function canApproveCommand(reference, preview, development) {
        const normalized = preview && {
            operationId: preview.commandId,
            version: preview.version,
            digest: preview.planDigest
        };
        const value = preview?.preview;
        return !!reference && preview?.displayExact === true
            && development?.matchesPreview?.(reference, normalized) === true
            && typeof value?.toolchainId === "string"
            && typeof value.displayName === "string"
            && typeof value.executable === "string"
            && /^[0-9a-f]{64}$/.test(value.executableHash || "")
            && Array.isArray(value.prefixArguments)
            && Array.isArray(value.arguments)
            && typeof value.relativeDirectory === "string"
            && Array.isArray(value.environmentKeys)
            && typeof value.environmentDigest === "string"
            && value.environmentOverrides && typeof value.environmentOverrides === "object"
            && Array.isArray(value.buildFiles)
            && Number.isSafeInteger(value.timeoutSeconds) && value.timeoutSeconds > 0
            && Number.isSafeInteger(value.maxOutputBytes) && value.maxOutputBytes > 0;
    }

    function createClientProjectPage({api, document, host = globalThis, getSessionEpoch}) {
        const $ = id => document.getElementById(id);
        const text = (tag, content, className) => {
            const node = document.createElement(tag);
            node.textContent = content || "";
            if (className) node.className = className;
            return node;
        };
        let active = true, epoch = getSessionEpoch(), capability = null, devices = [];
        let items = [], selected = null, path = "", cursor = "", preview = null;
        let lastCommand = null;
        let agents = [], conversation = null, messages = [], activeRunId = null;
        let conversationRequestId = null;
        let activeRunStatus = null, approvalPending = true, runPollTimer = null, runPollAttempts = 0;
        let approvalRenderSignature = null;
        let conversationRevision = 0;
        let listRevision = 0, detailRevision = 0, treeRevision = 0, textRevision = 0, searchRevision = 0;
        const controllers = new Set();
        const valid = () => active && epoch === getSessionEpoch();
        const status = (id, message = "", tone = "neutral") => {
            const node = $(id);
            if (!node) return;
            node.textContent = message;
            node.dataset.tone = tone;
        };

        async function request(url, options = {}) {
            if (!valid()) throw new Error("项目工作区已离开，请返回后重试。");
            const controller = new AbortController();
            controllers.add(controller);
            try {
                return await api.request(url, {
                    ...options,
                    // 消息、审批和 Run 沿用通用路由，仍须携带设备项目行动头，后端另按持久来源校验。
                    headers: {...options.headers, ...boundary},
                    signal: controller.signal
                });
            } finally {
                controllers.delete(controller);
            }
        }

        async function submitDecision(url, payload) {
            if (!valid()) throw new Error("项目工作区已离开，请核对原审批。");
            const controller = new AbortController();
            controllers.add(controller);
            let outcome = null, settled = false;
            try {
                // 通用会话只提供 SSE 审批恢复；决定接受不等于运行完成，断流后不能再次提交。
                await api.stream(url, {method: "POST", headers: boundary,
                    body: JSON.stringify(payload), signal: controller.signal}, event => {
                    if (!valid()) return;
                    if (event.type === "error") {
                        const value = event.data || {};
                        throw new Error(`${value.message || "审批恢复失败"}${value.code ? `（错误码：${value.code}）` : ""}${value.errorId ? `（错误编号：${value.errorId}）` : ""}`);
                    }
                    if (event.type === "completed" || event.type === "approval-required") {
                        settled = true;
                        outcome = event.data;
                    }
                });
                if (valid() && !settled) throw new Error("审批恢复未返回最终状态，请刷新会话核对。");
                return outcome;
            } finally {
                controllers.delete(controller);
            }
        }

        function renderCommandPreview(target, value) {
            const preview = value.preview || {};
            target.replaceChildren(
                text("h4", `${preview.displayName || preview.toolchainId} · ${preview.executable}`),
                text("p", `工作目录：${preview.relativeDirectory || "项目根"}`),
                text("p", `工具链：${preview.toolchainId} · 构建工具：${preview.build ? "是" : "否"}`),
                text("pre", ["固定参数", ...(preview.prefixArguments || []), "本次参数",
                    ...(preview.arguments || [])].join("\n")),
                text("p", `环境字段：${(preview.environmentKeys || []).join("、") || "无"} · 摘要 ${preview.environmentDigest}`),
                text("pre", `环境覆盖\n${
                    Object.entries(preview.environmentOverrides || {})
                        .map(([key, item]) => `${key}=${item}`).join("\n") || "无"
                }`),
                text("p", `构建文件：${(preview.buildFiles || []).join("、") || "未发现主构建配置"}`),
                text("p", `超时 ${preview.timeoutSeconds} 秒 · 输出上限 ${preview.maxOutputBytes} 字节 · 程序摘要 ${preview.executableHash}`)
            );
        }

        function switchSource(source) {
            const client = source === "client";
            $("serverProjectSource").hidden = client;
            $("clientProjectSource").hidden = !client;
            $("projectSourceServer").setAttribute("aria-pressed", String(!client));
            $("projectSourceClient").setAttribute("aria-pressed", String(client));
            $("projectSourceServer").className = client ? "button ghost compact" : "button primary compact";
            $("projectSourceClient").className = client ? "button primary compact" : "button ghost compact";
            if (client && capability?.enabled) reload();
        }

        function renderDevices() {
            const select = $("clientProjectDevice");
            select.replaceChildren();
            const activeDevices = devices.filter(device => device.state === "ACTIVE");
            if (!activeDevices.length) {
                select.append(new Option("尚无活动设备", ""));
                $("clientProjectAddBtn").disabled = true;
                return;
            }
            activeDevices.forEach(device => select.append(new Option(device.displayName, device.deviceId)));
            $("clientProjectAddBtn").disabled = capability?.enabled !== true;
        }

        async function loadDevices() {
            devices = await request("/api/local-client/devices");
            if (!Array.isArray(devices)) throw new Error("设备列表格式无效。");
            renderDevices();
        }

        function renderList() {
            const list = $("clientProjectList");
            list.replaceChildren();
            if (!items.length) {
                list.append(text("p", "尚无已配对电脑项目。", "empty-state"));
                return;
            }
            items.forEach(item => {
                const button = text("button", "", "resource-item local-project-item");
                button.type = "button";
                button.setAttribute("aria-pressed", String(selected?.id === item.id));
                button.classList.toggle("active", selected?.id === item.id);
                button.append(text("strong", item.name),
                    text("span", item.state === "ARCHIVED" ? "已归档" : `设备 ${item.deviceId.slice(0, 8)} · 可浏览`));
                button.addEventListener("click", () => select(item));
                list.append(button);
            });
        }

        async function reload() {
            if (!valid() || !capability?.enabled) return;
            const stamp = ++listRevision;
            status("clientProjectPageStatus", "正在读取设备项目…");
            const params = new URLSearchParams({
                limit: "50",
                includeArchived: String($("clientProjectIncludeArchived")?.checked === true)
            });
            try {
                const page = await request(`/api/client-projects?${params}`);
                if (!valid() || stamp !== listRevision) return;
                if (!Array.isArray(page)) throw new Error("设备项目列表格式无效。");
                items = page;
                if (selected) selected = items.find(item => item.id === selected.id) || null;
                renderList();
                status("clientProjectPageStatus", "");
            } catch (error) {
                if (valid() && stamp === listRevision) status("clientProjectPageStatus", error.message, "error");
            }
        }

        async function chooseFolder() {
            const deviceId = $("clientProjectDevice").value;
            if (!deviceId) throw new Error("请先选择一台活动设备。");
            const requestId = createRequestId(host.crypto);
            status("clientProjectPageStatus", "等待目标电脑打开文件夹选择器…");
            const task = await request("/api/client-projects/selections", {
                method: "POST",
                body: JSON.stringify({requestId, deviceId})
            });
            const result = await waitForTask({
                request, task, host, valid,
                statusPath: id => `/api/client-projects/selections/${encodeURIComponent(id)}`
            });
            if (!result.project) throw new Error("目录已选择，但项目登记结果不完整。");
            status("clientProjectPageStatus", "项目已添加。");
            await reload();
            select(result.project);
        }

        async function runQuery(kind, payload) {
            if (!selected || selected.state !== "ACTIVE") throw new Error("请选择活动设备项目。");
            const requestId = createRequestId(host.crypto);
            const task = await request(`/api/client-projects/${encodeURIComponent(selected.id)}/queries`, {
                method: "POST",
                body: JSON.stringify({
                    requestId, kind, path: payload.path || "", cursor: payload.cursor || "",
                    limit: payload.limit || 100, offset: payload.offset || 0,
                    maxBytes: payload.maxBytes || 65536, query: payload.query || "",
                    content: payload.content === true
                })
            });
            const result = await waitForTask({
                request, task, host, valid,
                statusPath: id => `/api/client-projects/queries/${encodeURIComponent(id)}`
            });
            return result.output || {};
        }

        async function select(project) {
            treeRevision++; textRevision++; searchRevision++; detailRevision++;
            conversationRevision++;
            selected = project;
            conversationRequestId = null;
            conversation = null;
            messages = [];
            activeRunId = null;
            renderList();
            $("clientProjectDetailEmpty").hidden = true;
            $("clientProjectDetailRegion").hidden = false;
            $("clientProjectTitle").textContent = project.name;
            $("clientProjectMetadata").textContent =
                `${project.state === "ACTIVE" ? "活动项目" : "已归档"} · 设备 ${project.deviceId}`;
            path = ""; cursor = ""; preview = null;
            lastCommand = null;
            $("clientProjectCommandId").value = "";
            $("clientProjectCommandOutput").textContent = "";
            $("clientProjectCommandMessage").textContent = "";
            $("clientProjectCommandAcknowledge").checked = false;
            $("clientProjectPreviewRegion").hidden = true;
            $("clientProjectSearchResults").replaceChildren();
            $("clientProjectChatBody").hidden = true;
            if (project.state === "ACTIVE") {
                if (!agents.length) await loadAgents();
                await listConversations();
                await loadTree("");
            } else {
                $("clientProjectConversationList").replaceChildren(text("p", "归档项目仅供查看历史接口预留，当前只读页面不开放新运行。", "field-help"));
            }
        }

        async function loadAgents() {
            const result = await request("/api/agents");
            if (!Array.isArray(result)) throw new Error("Agent 列表格式无效。");
            agents = result.filter(item => item.enabled);
            const select = $("clientProjectAgent");
            select.replaceChildren();
            agents.forEach(agent => select.append(new Option(agent.name, agent.id)));
            $("clientProjectNewConversation").disabled = selected?.state !== "ACTIVE" || !agents.length;
        }

        async function listConversations() {
            if (!selected) return;
            const projectId = selected.id;
            const result = await request(`/api/client-projects/${encodeURIComponent(projectId)}/conversations?limit=50`);
            if (!valid() || selected?.id !== projectId) return;
            if (!Array.isArray(result)) throw new Error("设备项目会话列表格式无效。");
            const target = $("clientProjectConversationList");
            target.replaceChildren();
            for (const item of result) {
                const button = text("button", item.title || "设备项目会话", "button ghost compact");
                button.type = "button";
                button.addEventListener("click", () => selectConversation(item));
                target.append(button);
            }
            if (!result.length) target.append(text("p", "尚无设备项目会话。", "field-help"));
        }

        async function selectConversation(value) {
            const selectionRevision = ++conversationRevision;
            conversation = value;
            messages = [];
            runPollAttempts = 0;
            $("clientProjectChatBody").hidden = false;
            $("clientProjectChatTitle").textContent = value.title || "设备项目会话";
            $("clientProjectChatStatus").textContent = "";
            await refreshConversation();
            if (valid() && selectionRevision === conversationRevision) scheduleRunPoll();
        }

        async function loadMessages() {
            if (!conversation) return;
            const conversationId = conversation.id, agentId = conversation.agentId;
            const selectionRevision = conversationRevision;
            const page = await request(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/messages?limit=50`);
            if (!valid() || conversation?.id !== conversationId
                    || selectionRevision !== conversationRevision) return;
            messages = page.items || [];
            renderMessages();
        }

        function renderMessages() {
            const target = $("clientProjectChatMessages");
            target.replaceChildren();
            if (!messages.length) {
                target.append(text("p", "发送第一条消息，Agent 可读取文件并通过精确审批执行文件变更。", "empty-state"));
                return;
            }
            for (const message of messages) {
                const article = text("article", "", "project-chat-message");
                article.dataset.role = message.role === "USER" ? "user" : "agent";
                article.append(text("strong", message.role === "USER" ? "你" : "Agent"));
                for (const block of message.contentBlocks || []) {
                    if (block.type === "TEXT") article.append(text("pre", block.text));
                    if (block.type === "TOOL_CALL") article.append(text("p", "工具调用摘要请在运行记录查看。", "field-help"));
                }
                target.append(article);
            }
        }

        async function refreshActiveRun() {
            if (!conversation) return;
            const run = await request(`/api/agents/${encodeURIComponent(conversation.agentId)}/conversations/${encodeURIComponent(conversation.id)}/active-run`);
            activeRunId = run?.id || null;
            activeRunStatus = run?.status || null;
            $("clientProjectRunStop").hidden = !activeRunId;
            return run;
        }

        async function loadApprovals() {
            if (!conversation) return;
            const agentId = conversation.agentId, conversationId = conversation.id;
            const stamp = conversationRevision;
            const page = await request(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/approvals`);
            if (!valid() || conversation?.id !== conversationId || stamp !== conversationRevision) return;
            const pending = (page.items || []).filter(item => item.status === "PENDING");
            approvalPending = pending.length > 0;
            const target = $("clientProjectApprovalItems");
            // 权威版本未变时保留已读取的精确预览和逐项选择，避免两秒轮询反复清空用户正在确认的审批。
            const signature = JSON.stringify({projectId: selected?.id, agentId, conversationId, stamp, pending});
            if (signature === approvalRenderSignature) { updateSendState(); return; }
            approvalRenderSignature = signature;
            target.replaceChildren();
            const projectDev = host.CmAgentProjectDevelopment;
            for (const approval of pending) {
                const card = text("section", "", "project-approval-card");
                card.append(text("h4", "核对本次文件变更或命令"));
                const choices = new Map();
                let editable = approval.canDecide === true;
                const submit = text("button", "提交本次决定", "button primary compact");
                submit.type = "button";
                submit.disabled = true;
                for (const item of approval.items || []) {
                    const reference = projectDev?.operationReference?.(item.inputSummary);
                    const fileChange = item.toolName === "client_project_apply_changes" && reference;
                    const command = item.toolName === "client_project_run_command" && reference;
                    const supported = fileChange || command;
                    const part = text("div", "", "project-approval-item");
                    part.append(text("strong", command ? "启动已预览固定工具链命令"
                            : supported ? "应用已预览文件变更" : "待核对操作"),
                        text("p", item.inputSummary, "field-help"));
                    const previewBody = text("div", "", "project-exact-preview");
                    const feedback = text("p", "", "form-status");
                    const reviewed = document.createElement("input");
                    reviewed.type = "checkbox";
                    reviewed.disabled = true;
                    const reviewedLabel = text("label", "");
                    reviewedLabel.append(reviewed, text("span", "我已核对以上精确差异"));
                    const decision = document.createElement("select");
                    decision.disabled = !supported;
                    const placeholder = new Option("请选择本次决定", "");
                    const deny = new Option("拒绝这次调用", "DENY");
                    const allow = new Option("允许这次调用", "APPROVE");
                    allow.disabled = true;
                    decision.append(placeholder, deny, allow);
                    const update = () => {
                        if (decision.value && supported) choices.set(item.itemId, decision.value);
                        else choices.delete(item.itemId);
                        allow.disabled = !reviewed.checked || reviewed.disabled || !supported;
                        if (decision.value === "APPROVE" && allow.disabled) decision.value = "";
                        submit.disabled = !editable || choices.size !== (approval.items || []).length;
                    };
                    reviewed.addEventListener("change", update);
                    decision.addEventListener("change", update);
                    const showPreview = text("button", "读取精确预览", "button ghost compact");
                    showPreview.type = "button";
                    showPreview.disabled = !supported;
                    showPreview.addEventListener("click", async () => {
                        if (!supported || !valid()) return;
                        reviewed.checked = false; reviewed.disabled = true; previewBody.replaceChildren(); update();
                        try {
                            const path = command
                                ? `/api/client-projects/${encodeURIComponent(selected.id)}/commands/${encodeURIComponent(reference.operationId)}/preview`
                                : `/api/client-projects/${encodeURIComponent(selected.id)}/changes/${encodeURIComponent(reference.operationId)}`;
                            const value = await request(path);
                            if (!valid() || conversation?.id !== conversationId || stamp !== conversationRevision) return;
                            if (command && !canApproveCommand(reference, value, projectDev)
                                    || fileChange && !canApproveChange(reference, value, projectDev)) {
                                throw new Error("操作版本、摘要或精确预览不完整，不能允许。");
                            }
                            if (command) {
                                renderCommandPreview(previewBody, value);
                                $("clientProjectCommandId").value = value.commandId;
                            } else {
                                projectDev.renderExactPreview(document, previewBody, value, false);
                            }
                            reviewed.disabled = !editable;
                            feedback.textContent = "预览与本次审批操作编号、版本和摘要一致。";
                        } catch (error) {
                            reviewed.checked = false; reviewed.disabled = true; update();
                            feedback.textContent = error.message;
                        }
                    });
                    part.append(showPreview, feedback, previewBody, reviewedLabel, decision);
                    card.append(part);
                }
                submit.addEventListener("click", async () => {
                    if (!valid() || !editable || choices.size !== (approval.items || []).length) return;
                    editable = false;
                    submit.disabled = true;
                    card.querySelectorAll?.("button,select,input").forEach(control => control.disabled = true);
                    try {
                        const outcome = await submitDecision(`/api/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/approvals/${encodeURIComponent(approval.approvalId)}/decision/stream`, {
                            expectedVersion: approval.version,
                                decisions: (approval.items || []).map(item => ({itemId: item.itemId,
                                    decision: choices.get(item.itemId)}))
                        });
                        if (!valid() || conversation?.id !== conversationId || stamp !== conversationRevision) return;
                        status("clientProjectChatStatus", outcome?.run?.status
                            ? `本轮状态：${outcome.run.status}。` : "审批决定已接受，正在核对运行状态。");
                        await refreshConversation();
                        scheduleRunPoll();
                    } catch (error) {
                        status("clientProjectChatStatus", `${error.message} 审批结果尚未确认，请刷新核对，不能再次提交。`, "error");
                    }
                });
                card.append(submit);
                target.append(card);
            }
            if (!pending.length) target.append(text("p", "当前没有待处理文件变更或命令审批。", "field-help"));
            updateSendState();
        }

        function updateSendState() {
            const busy = !conversation || !selected || selected.state !== "ACTIVE"
                || approvalPending || ["RUNNING", "WAITING_APPROVAL", "CANCELLING"].includes(activeRunStatus);
            $("clientProjectChatSend").disabled = busy;
        }

        function clearRunPoll() {
            if (runPollTimer != null) host.clearTimeout(runPollTimer);
            runPollTimer = null;
        }

        function scheduleRunPoll() {
            clearRunPoll();
            if (!valid() || !conversation || runPollAttempts >= 30) return;
            if (!approvalPending && !["RUNNING", "WAITING_APPROVAL", "CANCELLING"].includes(activeRunStatus)) return;
            runPollTimer = host.setTimeout(async () => {
                runPollTimer = null;
                runPollAttempts++;
                try { await refreshConversation(); }
                catch (error) { status("clientProjectChatStatus", "运行状态读取未完成，请手动刷新核对。", "error"); }
                scheduleRunPoll();
            }, 2000);
            runPollTimer?.unref?.();
            updateSendState();
        }

        async function refreshConversation() {
            if (!conversation) return;
            await Promise.all([loadMessages(), refreshActiveRun(), loadApprovals()]);
            updateSendState();
        }

        async function createConversation() {
            if (!selected || selected.state !== "ACTIVE") return;
            const agentId = $("clientProjectAgent").value;
            if (!agentId) throw new Error("请选择 Agent。");
            $("clientProjectNewConversation").disabled = true;
            try {
                const result = await request(`/api/client-projects/${encodeURIComponent(selected.id)}/conversations`, {
                    method: "POST",
                    body: JSON.stringify({requestId: conversationRequestId ||= createRequestId(host.crypto), agentId})
                });
                conversationRequestId = null;
                await listConversations();
                await selectConversation(result);
            } finally {
                $("clientProjectNewConversation").disabled = selected?.state !== "ACTIVE" || !agents.length;
            }
        }

        async function sendTask(event) {
            event.preventDefault();
            if (!conversation) throw new Error("请先选择或新建设备项目会话。");
            const input = $("clientProjectChatInput").value.trim();
            if (!input) return;
            $("clientProjectChatSend").disabled = true;
            $("clientProjectRunStop").hidden = true;
            status("clientProjectChatStatus", "正在执行设备项目会话…");
            try {
                const outcome = await request(`/api/agents/${encodeURIComponent(conversation.agentId)}/conversations/${encodeURIComponent(conversation.id)}/messages`, {
                    method: "POST",
                    body: JSON.stringify({input, projectAuthorizationMode: "ASK_ON_EFFECT"})
                });
                $("clientProjectChatInput").value = "";
                activeRunId = outcome.run?.finishedAt ? null : outcome.run?.id || null;
                activeRunStatus = outcome.run?.status || null;
                $("clientProjectRunStop").hidden = !activeRunId;
                status("clientProjectChatStatus", `本轮状态：${outcome.run?.status || "未知"}。${outcome.run?.errorMessage || ""}`);
                await refreshConversation();
                scheduleRunPoll();
            } catch (error) {
                status("clientProjectChatStatus", `${error.message} 结果尚未确认，请核对原会话，不要重复发送。`, "error");
            } finally {
                updateSendState();
            }
        }

        async function stopRun() {
            if (!conversation) return;
            try {
                const active = await request(`/api/agents/${encodeURIComponent(conversation.agentId)}/conversations/${encodeURIComponent(conversation.id)}/active-run`);
                if (!active?.id) {
                    activeRunId = null;
                activeRunStatus = null;
                    $("clientProjectRunStop").hidden = true;
                    status("clientProjectChatStatus", "当前没有活动运行。");
                    return;
                }
                await request(`/api/agents/${encodeURIComponent(conversation.agentId)}/conversations/${encodeURIComponent(conversation.id)}/runs/${encodeURIComponent(active.id)}/cancel`, {
                    method: "POST"
                });
                activeRunId = active.id;
                activeRunStatus = "CANCELLING";
                status("clientProjectChatStatus", "已请求停止；请在运行记录核对实际终态。");
                scheduleRunPoll();
            } catch (error) {
                status("clientProjectChatStatus", `${error.message} 停止请求未确认。`, "error");
            }
        }

        async function loadTree(directory, append = false) {
            if (!selected || selected.state !== "ACTIVE") return;
            const projectId = selected.id;
            const stamp = ++treeRevision;
            if (!append) {
                path = directory;
                cursor = "";
                $("clientProjectFileList").replaceChildren();
                preview = null;
                $("clientProjectPreviewRegion").hidden = true;
            }
            $("clientProjectPath").textContent = path || "项目根目录";
            $("clientProjectTreeParent").disabled = !path;
            $("clientProjectTreeMore").disabled = true;
            status("clientProjectTreeStatus", "正在通过目标电脑读取目录…");
            try {
                const page = await runQuery("LIST", {path: directory, cursor, limit: 100});
                if (!valid() || stamp !== treeRevision || selected?.id !== projectId) return;
                const list = $("clientProjectFileList");
                if (!page.items?.length && !append) list.append(text("p", "这个目录没有可展示的条目。", "empty-state"));
                for (const item of page.items || []) {
                    const button = text("button", "", "project-file-row");
                    button.type = "button";
                    const directoryItem = item.kind === "DIRECTORY";
                    const readable = item.readable === true && item.kind === "FILE";
                    button.disabled = !directoryItem && !readable;
                    button.append(text("span", directoryItem ? "目录" : "文件", "project-file-kind"),
                        text("span", item.name, "project-file-name"),
                        text("span", directoryItem ? "打开" : readable ? "预览" : "不可预览", "project-file-action"));
                    button.addEventListener("click", () =>
                        directoryItem ? loadTree(item.path) : loadText(item.path));
                    list.append(button);
                }
                cursor = page.nextCursor || "";
                $("clientProjectTreeMore").hidden = !cursor;
                status("clientProjectTreeStatus", page.partial ? "目录结果已截断。" : "");
            } catch (error) {
                if (valid() && stamp === treeRevision) status("clientProjectTreeStatus", error.message, "error");
            } finally {
                if (valid() && stamp === treeRevision) $("clientProjectTreeMore").disabled = false;
            }
        }

        async function loadText(file, offset = 0) {
            if (!selected || selected.state !== "ACTIVE") return;
            const projectId = selected.id;
            const stamp = ++textRevision;
            const previous = preview;
            preview = null;
            $("clientProjectPreviewRegion").hidden = false;
            $("clientProjectPreviewTitle").textContent = file;
            $("clientProjectPreviewContent").textContent = "";
            $("clientProjectTextNext").hidden = true;
            status("clientProjectPreviewStatus", "正在通过目标电脑读取脱敏文本…");
            try {
                const chunk = await runQuery("READ", {path: file, offset, maxBytes: 65536});
                if (!valid() || stamp !== textRevision || selected?.id !== projectId) return;
                if (offset > 0 && previous?.sha256 && previous.sha256 !== chunk.sha256)
                    throw new Error("文件已变化，请从头重新打开。");
                preview = chunk;
                $("clientProjectPreviewContent").textContent = chunk.content || "";
                $("clientProjectTextNext").hidden = !chunk.hasMore;
                status("clientProjectPreviewStatus",
                    `当前片段 ${chunk.offset}–${chunk.nextOffset} / ${chunk.totalBytes} 字节`);
            } catch (error) {
                if (valid() && stamp === textRevision) status("clientProjectPreviewStatus", error.message, "error");
            }
        }

        async function search(event) {
            event.preventDefault();
            if (!selected || selected.state !== "ACTIVE") return;
            const query = $("clientProjectSearchQuery").value.trim();
            if (!query) {
                status("clientProjectSearchStatus", "请输入文件名或正文关键词。", "error");
                return;
            }
            const projectId = selected.id, stamp = ++searchRevision;
            $("clientProjectSearchResults").replaceChildren();
            $("clientProjectSearchSubmit").disabled = true;
            status("clientProjectSearchStatus", "正在通过目标电脑执行有界搜索…");
            try {
                const result = await runQuery("SEARCH", {
                    query, content: $("clientProjectSearchContent").checked === true
                });
                if (!valid() || stamp !== searchRevision || selected?.id !== projectId) return;
                for (const item of result.matches || []) {
                    const entry = text("button", "", "project-file-row");
                    entry.type = "button";
                    entry.append(text("span", item.contentMatch ? "正文" : "路径", "project-file-kind"),
                        text("span", item.path, "project-file-name"), text("span", "预览", "project-file-action"));
                    entry.addEventListener("click", () => {
                        if (valid() && stamp === searchRevision && selected?.id === projectId)
                            loadText(item.path);
                    });
                    $("clientProjectSearchResults").append(entry);
                    if (item.excerpt) $("clientProjectSearchResults").append(text("p", item.excerpt, "field-help"));
                }
                status("clientProjectSearchStatus",
                    `找到 ${result.matches?.length || 0} 项 · 扫描 ${result.scanned || 0} 项` +
                    `${result.partial ? " · 结果不完整，请缩小关键词" : ""}`);
            } catch (error) {
                if (valid() && stamp === searchRevision)
                    status("clientProjectSearchStatus", error.message, "error");
            } finally {
                if (valid() && stamp === searchRevision) $("clientProjectSearchSubmit").disabled = false;
            }
        }

        function selectedCommandId() {
            const value = $("clientProjectCommandId").value.trim();
            if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value))
                throw new Error("请输入有效命令编号。");
            if (!selected || selected.state !== "ACTIVE") throw new Error("请选择活动设备项目。");
            return value;
        }

        async function refreshCommandStatus(commandId = selectedCommandId()) {
            const projectId = selected.id;
            const stamp = ++detailRevision;
            status("clientProjectCommandMessage", "正在请求目标电脑核对命令状态…");
            const task = await request(`/api/client-projects/${encodeURIComponent(projectId)}/commands/${encodeURIComponent(commandId)}/status`, {method: "POST"});
            await waitForTask({request, task, host, valid,
                statusPath: id => `/api/client-projects/queries/${encodeURIComponent(id)}`});
            const command = await request(`/api/client-projects/${encodeURIComponent(projectId)}/commands/${encodeURIComponent(commandId)}`);
            if (!valid() || stamp !== detailRevision || selected?.id !== projectId) return;
            lastCommand = command;
            $("clientProjectCommandOutput").textContent = command.outputAvailable
                ? `状态：${command.state} · 退出码：${command.exitCode ?? "未确认"}\n` +
                    `${command.stopRequested ? "已登记停止请求，仍需确认退出。\n" : ""}` +
                    `标准输出\n${command.stdout || ""}\n标准错误\n${command.stderr || ""}`
                : `状态：${command.state} · 输出不可用${command.stopRequested ? " · 已登记停止请求" : ""}`;
            status("clientProjectCommandMessage", command.state === "SUCCEEDED"
                ? "命令已成功退出。" : command.state === "UNKNOWN"
                    ? "命令结果未知，请停止并人工核对，不能自动重放。" : "命令尚未确认成功退出。",
                command.state === "SUCCEEDED" ? "success" : command.state === "UNKNOWN" ? "error" : "neutral");
            return command;
        }

        async function stopCommand() {
            const commandId = selectedCommandId();
            const projectId = selected.id;
            status("clientProjectCommandMessage", "正在请求停止并等待设备受理…");
            const task = await request(`/api/client-projects/${encodeURIComponent(projectId)}/commands/${encodeURIComponent(commandId)}/stop`, {method: "POST"});
            await waitForTask({request, task, host, valid,
                statusPath: id => `/api/client-projects/queries/${encodeURIComponent(id)}`});
            await refreshCommandStatus(commandId);
        }

        async function resolveCommandUnknown() {
            const commandId = selectedCommandId();
            if (!lastCommand || lastCommand.commandId !== commandId
                    || lastCommand.state !== "UNKNOWN")
                throw new Error("请先核对并确认命令处于 UNKNOWN。");
            if (!$("clientProjectCommandAcknowledge").checked)
                throw new Error("请先明确接受可能发生的外部副作用。");
            const value = await request(`/api/client-projects/${encodeURIComponent(selected.id)}/commands/${encodeURIComponent(commandId)}/resolve-unknown`, {
                method: "POST",
                body: JSON.stringify({expectedVersion: lastCommand.version,
                    acknowledgeExternalEffects: true})
            });
            if (!valid()) return;
            lastCommand = value;
            status("clientProjectCommandMessage",
                "已记录 UNKNOWN 对账；项目隔离保持，只有可信退出证据或受控处理流程可以解除。",
                "error");
        }

        async function mount() {
            $("projectSourceServer").addEventListener("click", () => switchSource("server"));
            $("projectSourceClient").addEventListener("click", () => switchSource("client"));
            $("clientProjectRefresh").addEventListener("click", reload);
            $("clientProjectIncludeArchived").addEventListener("change", reload);
            $("clientProjectAddBtn").addEventListener("click", () => chooseFolder().catch(error =>
                status("clientProjectPageStatus", error.message, "error")));
            $("clientProjectTreeParent").addEventListener("click", () =>
                loadTree(path.split("/").slice(0, -1).join("/")));
            $("clientProjectTreeRefresh").addEventListener("click", () => loadTree(path));
            $("clientProjectTreeMore").addEventListener("click", () => loadTree(path, true));
            $("clientProjectTextNext").addEventListener("click", () =>
                preview && loadText(preview.path, preview.nextOffset));
            $("clientProjectSearchForm").addEventListener("submit", search);
            $("clientProjectNewConversation").addEventListener("click", () =>
                createConversation().catch(error =>
                    status("clientProjectChatStatus", error.message, "error")));
            $("clientProjectConversationRefresh").addEventListener("click", () =>
                listConversations().catch(error =>
                    status("clientProjectChatStatus", error.message, "error")));
            $("clientProjectChatForm").addEventListener("submit", sendTask);
            $("clientProjectRunStop").addEventListener("click", stopRun);
            $("clientProjectCommandStatus").addEventListener("click", () => refreshCommandStatus().catch(error =>
                status("clientProjectCommandMessage", error.message, "error")));
            $("clientProjectCommandStop").addEventListener("click", () => stopCommand().catch(error =>
                status("clientProjectCommandMessage", error.message, "error")));
            $("clientProjectCommandResolve").addEventListener("click", () =>
                resolveCommandUnknown().catch(error =>
                    status("clientProjectCommandMessage", error.message, "error")));
            try {
                const result = await request("/api/client-projects/capabilities");
                if (!valid()) return;
                capability = result;
                $("projectSourceClient").hidden = result.enabled !== true;
                $("clientProjectCapabilityNote").textContent = result.enabled === true
                    ? `当前开放有界目录、脱敏文本、搜索、${result.changes === true ? "逐次审批文件变更" : "只读项目会话"}${result.commands === true ? "和固定工具链命令生命周期" : ""}。`
                    : "当前服务未开放已配对电脑项目。";
                if (result.enabled === true) {
                    await loadDevices();
                    await reload();
                } else {
                    $("clientProjectAddBtn").disabled = true;
                }
            } catch (error) {
                if (valid()) status("clientProjectPageStatus", error.message, "error");
            }
        }

        return {
            mount,
            dispose() {
                active = false;
                listRevision++; detailRevision++; treeRevision++; textRevision++; searchRevision++;
                conversationRevision++;
                clearRunPoll();
                controllers.forEach(controller => controller.abort());
                controllers.clear();
                preview = null; selected = null; items = [];
                conversationRequestId = null;
                activeRunId = null; activeRunStatus = null; approvalPending = true;
                approvalRenderSignature = null;
                lastCommand = null;
                $("clientProjectApprovalItems")?.replaceChildren();
                $("clientProjectSearchResults")?.replaceChildren();
                if ($("clientProjectPreviewContent")) $("clientProjectPreviewContent").textContent = "";
            }
        };
    }

    return {createRequestId, waitForTask, canApproveChange, canApproveCommand,
        createClientProjectPage};
});
