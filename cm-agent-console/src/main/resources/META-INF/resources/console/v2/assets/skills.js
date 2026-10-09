(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentSkills = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    function createRequestScope(getSessionEpoch) {
        let revision = 0;
        return {
            issue(key) { return {key, revision: ++revision, session: getSessionEpoch()}; },
            isCurrent(ticket) { return ticket && ticket.revision === revision && ticket.session === getSessionEpoch(); },
            invalidate() { revision += 1; }
        };
    }

    function canPublishTrial(trial, candidateVersionId, mappingRevision) {
        return Boolean(trial && trial.status === "PASSED" && trial.qualifiesRelease
            && trial.versionId === candidateVersionId && trial.mappingRevision === mappingRevision);
    }

    function eligibleTrialAgents(agents) {
        return (Array.isArray(agents) ? agents : [])
            .filter((agent) => agent && agent.enabled && typeof agent.id === "string" && agent.id.length > 0);
    }

    function runStatusLabel(status) {
        return ({
            RUNNING: "运行中",
            WAITING_APPROVAL: "等待审批",
            SUCCEEDED: "成功",
            FAILED: "失败",
            DENIED: "已拒绝"
        })[status] || status || "未知";
    }

    function trialStatusLabel(status) {
        return ({
            RUNNING: "试运行进行中",
            WAITING_APPROVAL: "等待审批",
            PASSED: "试运行通过，可作为发布依据",
            NOT_TRIGGERED: "运行完成，但未读取目标技能",
            FAILED: "试运行失败"
        })[status] || "试运行状态未知";
    }

    function versionStateLabel(state) {
        return ({CANDIDATE: "待验证候选", CURRENT_PUBLISHED: "当前正式版本",
            PREVIOUSLY_PUBLISHED: "曾发布，可回滚", UNPUBLISHED_HISTORY: "未发布历史"})[state] || "未知版本状态";
    }

    // 恢复展示只读取权威状态；批准或网络断线均不能触发再次提交或自动执行工具。
    async function loadTrialState(request, skillId, runId) {
        const base = `/api/skills/${encodeURIComponent(skillId)}/trials/${encodeURIComponent(runId)}`;
        const trial = await request(base);
        const approval = await request(`${base}/approvals/current`);
        return {trial, approval};
    }

    function createSkillPage({api, getSessionEpoch, getPermissions, document, artifacts}) {
        const scope = createRequestScope(getSessionEpoch);
        let selectedId = "";
        let selectedMappingRevision = null;
        let uploadBusy = false;
        const byId = (id) => document.getElementById(id);
        const status = (message, tone = "neutral") => {
            const node = byId("skillPageStatus");
            node.textContent = message || "";
            node.dataset.tone = tone;
        };
        const text = (tag, value, className) => {
            const node = document.createElement(tag); node.textContent = value; if (className) node.className = className; return node;
        };
        const statusBadge = (enabled) => {
            const badge = text("span", enabled ? "已启用" : "未启用", "skill-state-badge");
            badge.dataset.state = enabled ? "enabled" : "disabled";
            return badge;
        };
        const selectListItem = (id) => {
            byId("skillList").querySelectorAll(".resource-item").forEach((item) => {
                const active = item.dataset.skillId === id;
                item.classList.toggle("active", active);
                item.setAttribute("aria-pressed", String(active));
            });
        };
        async function reload() {
            const ticket = scope.issue("list");
            status("正在加载技能…");
            try {
                const page = await api.request("/api/skills?page=0&size=50");
                if (!scope.isCurrent(ticket)) return;
                const items = Array.isArray(page?.items) ? page.items : Array.isArray(page) ? page : [];
                const list = byId("skillList"); list.replaceChildren();
                if (!items.length) {
                    const empty = document.createElement("div"); empty.className = "skill-list-empty";
                    empty.append(text("strong", "尚未导入技能"), text("p", "展开下方“导入新技能”，上传 ZIP 后开始核验与发布。"));
                    list.append(empty);
                }
                items.forEach((skill) => {
                    const button = document.createElement("button"); button.type = "button"; button.className = "resource-item skill-resource-item";
                    button.dataset.skillId = skill.id || ""; button.setAttribute("aria-pressed", "false");
                    const heading = document.createElement("span"); heading.className = "skill-resource-heading";
                    heading.append(text("strong", skill.name || "未命名技能"), statusBadge(skill.enabled));
                    button.append(heading, text("span", skill.description || "未提供技能说明", "skill-resource-description"),
                        text("span", `${skill.versionNo ? `v${skill.versionNo} · ` : ""}${skill.candidateVersionId ? "有候选待验证" : skill.publishedVersionId ? "已正式发布" : "尚未发布"}`, "skill-list-version"));
                    button.addEventListener("click", () => select(skill.id)); list.append(button);
                });
                status("");
                return ticket;
            } catch (error) { if (scope.isCurrent(ticket)) status(error.message, "error"); }
        }
        async function select(id) {
            selectedId = id; selectedMappingRevision = null; selectListItem(id); const ticket = scope.issue("detail");
            byId("skillDetail").replaceChildren(text("p", "正在加载技能详情…", "empty-state"));
            try {
                const skill = await api.request(`/api/skills/${encodeURIComponent(id)}`);
                if (!scope.isCurrent(ticket)) return;
                const summary = skill.summary || skill;
                const detail = byId("skillDetail"); detail.classList.add("skill-detail-content"); detail.replaceChildren();
                const header = document.createElement("div"); header.className = "skill-detail-header";
                const title = document.createElement("div"); title.append(text("h2", summary.name || "技能详情"), text("p", summary.description || "未提供描述"));
                header.append(title, statusBadge(summary.enabled));
                const metadata = document.createElement("dl"); metadata.className = "skill-detail-metadata";
                metadata.append(text("dt", "当前查看版本"), text("dd", summary.versionNo ? `v${summary.versionNo}` : "版本信息不可用"), text("dt", "使用状态"), text("dd", summary.enabled
                    ? summary.publishedVersionId ? "已启用；正式运行按 Agent 绑定策略使用已发布版本" : "已启用但尚未发布，暂不能用于正式运行"
                    : "停用后不可绑定或读取"));
                detail.append(header, metadata);
                if (getPermissions().includes("skill:write")) {
                    const upgrade = document.createElement("details"); upgrade.className = "skill-upgrade";
                    upgrade.append(text("summary", "上传新版本"));
                    const form = document.createElement("form"); form.className = "skill-version-upload-form";
                    const label = text("label", "新版本 ZIP 技能包");
                    const file = document.createElement("input"); file.type = "file"; file.accept = ".zip,application/zip"; file.required = true;
                    label.append(file);
                    const help = text("p", `包内 SKILL.md 的 name 必须保持为 ${summary.name || "当前技能名称"}。上传只形成候选，原正式版本继续使用。${summary.candidateVersionId ? "新内容会替换当前候选，此前试运行不能用于发布新候选。" : "验证并发布后，新建运行才会按绑定策略使用它。"}`, "field-help");
                    const submit = text("button", "上传为候选版本", "button primary"); submit.type = "submit";
                    const refresh = text("button", "刷新技能详情", "button ghost"); refresh.type = "button";
                    refresh.addEventListener("click", () => { if (!uploadBusy) select(id); });
                    const actions = document.createElement("div"); actions.className = "skill-upload-actions"; actions.append(submit, refresh);
                    const result = text("p", "", "form-status"); result.setAttribute("aria-live", "polite");
                    form.append(label, help, actions, result); upgrade.append(form); detail.append(upgrade);
                    form.addEventListener("submit", async (event) => {
                        event.preventDefault();
                        if (uploadBusy || !scope.isCurrent(ticket) || !getPermissions().includes("skill:write")) return;
                        const selectedFile = file.files?.[0];
                        if (!selectedFile) { result.textContent = "请选择新版本 ZIP 技能包。"; result.dataset.tone = "error"; return; }
                        const body = new FormData(); body.append("file", selectedFile, selectedFile.name);
                        // 双指针来自当前详情快照，不能在发送前悄悄刷新，避免覆盖其他管理员刚上传或发布的版本。
                        if (summary.candidateVersionId) body.append("expectedCandidateVersionId", summary.candidateVersionId);
                        if (summary.publishedVersionId) body.append("expectedPublishedVersionId", summary.publishedVersionId);
                        uploadBusy = true; submit.disabled = true; file.disabled = true; refresh.disabled = true;
                        submit.textContent = "正在上传…"; result.textContent = "正在上传候选版本，请稍候。"; result.dataset.tone = "neutral";
                        try {
                            const updated = await api.request(`/api/skills/${encodeURIComponent(id)}/versions`, {method: "POST", body});
                            if (!scope.isCurrent(ticket)) return;
                            const next = updated.summary || updated;
                            const unchanged = next.candidateVersionId === summary.candidateVersionId && next.publishedVersionId === summary.publishedVersionId;
                            const message = unchanged ? "内容与现有版本一致，未新增版本。" : "新候选已上传；请完成依赖预检、试运行和发布。原正式版本未变更。";
                            const listTicket = await reload();
                            if (!scope.isCurrent(listTicket)) return;
                            const detailTicket = await select(id);
                            if (scope.isCurrent(detailTicket)) status(message, "success");
                        } catch (error) {
                            if (!scope.isCurrent(ticket)) return;
                            result.textContent = `${error.message} ${error.code === "SKILL_CANDIDATE_CONFLICT" ? "请刷新技能详情，核对最新候选与正式版本后重新选择 ZIP。" : "文件已保留；请核对原因后重试。若连接中断，请先刷新详情确认上传结果。"}`;
                            result.dataset.tone = "error";
                        } finally {
                            uploadBusy = false; submit.disabled = false; file.disabled = false; refresh.disabled = false; submit.textContent = "上传为候选版本";
                        }
                    });
                }
                const release = document.createElement("section"); release.className = "skill-release-workspace";
                release.append(text("h3", "发布工作区"), text("p", "按“依赖预检 → 指定版本试运行 → 人工发布”推进；候选不会进入正式运行。", "field-help"));
                const pointers = document.createElement("dl"); pointers.className = "skill-release-pointers";
                const candidate = text("dd", summary.candidateVersionId ? "正在读取版本号…" : "当前没有候选");
                const published = text("dd", summary.publishedVersionId ? "正在读取版本号…" : "尚未发布");
                pointers.append(text("dt", "候选版本"), candidate, text("dt", "正式版本"), published);
                release.append(pointers);
                const dependencies = document.createElement("div"); dependencies.className = "skill-dependency-summary";
                dependencies.append(text("strong", "依赖映射"), text("p", "正在读取当前候选的工具依赖。", "field-help")); release.append(dependencies);
                try {
                    const view = await api.request(`/api/skills/${encodeURIComponent(id)}/dependencies`);
                    if (!scope.isCurrent(ticket)) return;
                    selectedMappingRevision = view.revision;
                    const rows = document.createElement("ul"); rows.className = "skill-dependency-list";
                    const entries = Array.isArray(view?.items) ? view.items : [];
                    if (!entries.length) rows.append(text("li", "此版本未声明工具依赖。"));
                    let tools = [];
                    if (getPermissions().includes("skill:write") && entries.length) {
                        const page = await api.request("/api/tools?page=0&size=100");
                        if (!scope.isCurrent(ticket)) return;
                        tools = Array.isArray(page?.items) ? page.items.filter((item) => item.enabled) : [];
                    }
                    entries.forEach((entry) => {
                        const row = document.createElement("li");
                        row.append(text("span", `${entry.logicalKey} · ${entry.required ? "必需" : "可选"} · ${entry.mappedToolId ? "已映射" : "等待映射"}`));
                        if (getPermissions().includes("skill:write")) {
                            const toolSelect = document.createElement("select"); toolSelect.className = "inline-select";
                            const placeholder = document.createElement("option"); placeholder.value = ""; placeholder.textContent = tools.length ? "选择已启用工具" : "没有可映射的已启用工具"; toolSelect.append(placeholder);
                            tools.forEach((tool) => { const option = document.createElement("option"); option.value = tool.id; option.textContent = tool.name || tool.id; toolSelect.append(option); });
                            toolSelect.value = entry.mappedToolId || "";
                            const save = document.createElement("button"); save.type = "button"; save.className = "button ghost"; save.textContent = "保存映射";
                            save.addEventListener("click", async () => {
                                if (!toolSelect.value) { status("请选择一个已启用工具后再保存映射。", "error"); return; }
                                save.disabled = true;
                                try {
                                    await api.request(`/api/skills/${encodeURIComponent(id)}/dependencies/${encodeURIComponent(entry.logicalKey)}`, {method: "PUT", body: JSON.stringify({toolId: toolSelect.value, expectedRevision: view.revision})});
                                    status("依赖映射已保存；此前试运行需要重新执行。", "success"); await select(id);
                                } catch (error) { status(error.message, "error"); } finally { save.disabled = false; }
                            });
                            row.append(toolSelect, save);
                        }
                        rows.append(row);
                    });
                    dependencies.replaceChildren(text("strong", "依赖映射"), rows);
                } catch (error) { dependencies.replaceChildren(text("strong", "依赖映射"), text("p", error.message, "field-help")); }
                const history = document.createElement("details"); history.className = "skill-version-history";
                history.append(text("summary", "版本历史与回滚"), text("p", "回滚会重新发布历史版本，只影响之后按跟随策略创建的 Run。", "field-help"));
                const historyList = document.createElement("ul"); historyList.className = "skill-dependency-list";
                history.append(historyList); release.append(history);
                try {
                    const versions = await api.request(`/api/skills/${encodeURIComponent(id)}/versions`);
                    if (!scope.isCurrent(ticket)) return;
                    [[candidate, summary.candidateVersionId], [published, summary.publishedVersionId]].forEach(([node, versionId]) => {
                        if (!versionId) return;
                        const version = versions.find((item) => item.versionId === versionId);
                        node.textContent = version ? `v${version.versionNo}` : "版本号不可用";
                        node.title = versionId;
                    });
                    if (!versions.length) historyList.append(text("li", "尚无版本历史。"));
                    versions.forEach((version) => {
                        const row = document.createElement("li");
                        row.append(text("span", `v${version.versionNo} · ${versionStateLabel(version.state)}`));
                        if (version.state === "PREVIOUSLY_PUBLISHED" && getPermissions().includes("skill:write")) {
                            const rollback = document.createElement("button"); rollback.type = "button"; rollback.className = "button ghost"; rollback.textContent = "回滚到此版本";
                            rollback.addEventListener("click", async () => {
                                if (!window.confirm(`确认将技能回滚并重新发布 v${version.versionNo} 吗？`)) return;
                                rollback.disabled = true;
                                try {
                                    await api.request(`/api/skills/${encodeURIComponent(id)}/rollbacks`, {method: "POST", body: JSON.stringify({targetVersionId: version.versionId, expectedPublishedVersionId: summary.publishedVersionId})});
                                    status(`已重新发布 v${version.versionNo}。`, "success"); await reload(); await select(id);
                                } catch (error) { status(error.message, "error"); } finally { rollback.disabled = false; }
                            });
                            row.append(rollback);
                        }
                        historyList.append(row);
                    });
                } catch (error) {
                    if (summary.candidateVersionId) candidate.textContent = "版本信息读取失败";
                    if (summary.publishedVersionId) published.textContent = "版本信息读取失败";
                    historyList.append(text("li", `版本历史读取失败：${error.message}`));
                }
                if (summary.candidateVersionId && getPermissions().includes("skill:write")) {
                    const preflight = document.createElement("div"); preflight.className = "skill-preflight-actions";
                    const check = document.createElement("button"); check.type = "button"; check.className = "button ghost"; check.textContent = "执行候选结构预检";
                    const result = document.createElement("p"); result.className = "form-status"; result.setAttribute("aria-live", "polite");
                    check.addEventListener("click", async () => {
                        check.disabled = true; result.textContent = "正在验证候选版本依赖…";
                        try {
                            const checked = await api.request(`/api/skills/${encodeURIComponent(id)}/preflights?versionId=${encodeURIComponent(summary.candidateVersionId)}&scope=STRUCTURAL`, {method: "POST"});
                            const status = preflightStatus(checked);
                            result.textContent = status === "PASSED" ? "结构预检通过。" : `结构预检结果：${status}。请修复失败项后重新执行。`;
                            result.dataset.tone = status === "PASSED" ? "success" : "neutral";
                        } catch (error) { result.textContent = error.message; result.dataset.tone = "error"; } finally { check.disabled = false; }
                    });
                    preflight.append(check, result); release.append(preflight);
                }
                if (summary.candidateVersionId && getPermissions().includes("skill:write")) {
                    const trial = document.createElement("form"); trial.className = "skill-trial-form";
                    trial.append(text("h4", "指定版本试运行"), text("p", "使用真实 Agent、模型和工具治理链路；工具可能产生真实副作用。", "field-help"));
                    const agentLabel = text("label", "目标 Agent");
                    const agentSelect = document.createElement("select"); agentSelect.required = true; agentSelect.setAttribute("aria-label", "目标 Agent");
                    const agentPlaceholder = document.createElement("option"); agentPlaceholder.value = ""; agentPlaceholder.disabled = true; agentPlaceholder.selected = true;
                    agentPlaceholder.textContent = "正在加载可用 Agent…"; agentSelect.append(agentPlaceholder); agentSelect.disabled = true;
                    agentLabel.append(agentSelect);
                    const agentHelp = text("p", "仅显示当前租户已启用的 Agent。", "field-help"); agentHelp.setAttribute("aria-live", "polite");
                    const inputLabel = text("label", "测试输入"); const input = document.createElement("textarea"); input.required = true; input.placeholder = "描述希望模型使用此技能完成的任务"; inputLabel.append(input);
                    const run = document.createElement("button"); run.type = "submit"; run.className = "button primary"; run.textContent = "开始试运行"; run.disabled = true;
                    const result = document.createElement("p"); result.className = "form-status"; result.setAttribute("aria-live", "polite");
                    const preview = document.createElement("section"); preview.className = "skill-trial-preview";
                    const publishActions = document.createElement("div"); publishActions.className = "skill-preflight-actions";
                    preview.setAttribute("aria-label", "试运行结果预览");
                    const renderTrialPreview = (trialResponse) => {
                        const data = trialResponse && trialResponse.preview;
                        preview.replaceChildren();
                        if (!data) return;
                        preview.append(text("h5", "运行结果"));
                        preview.append(text("p", `${trialStatusLabel(trialResponse.status)} · Run：${runStatusLabel(data.status)}`, "field-help"));
                        preview.append(text("h6", "最终输出"));
                        preview.append(text("pre", data.output || (data.status === "WAITING_APPROVAL" ? "当前运行等待审批，完成后会更新输出。" : "本次运行没有文本输出。"), "skill-trial-output"));
                        if (trialResponse.runId && artifacts) {
                            const files = document.createElement("section");
                            preview.append(files);
                            artifacts.mount(files, `/api/skills/${encodeURIComponent(selectedId)}/trials/${encodeURIComponent(trialResponse.runId)}/artifacts`);
                        }
                        if (data.errorMessage) {
                            const error = text("p", data.errorMessage, "form-status");
                            error.dataset.tone = "error";
                            preview.append(error);
                        }
                        const toolCalls = Array.isArray(data.toolCalls) ? data.toolCalls : [];
                        preview.append(text("h6", `工具调用（${toolCalls.length}）`));
                        if (toolCalls.length === 0) {
                            preview.append(text("p", data.status === "WAITING_APPROVAL" ? "审批完成前暂无已记录的工具调用摘要。" : "本次运行没有已记录的工具调用。", "field-help"));
                        }
                        toolCalls.forEach((call) => {
                            const item = document.createElement("details"); item.className = "skill-trial-tool-preview";
                            const summary = document.createElement("summary");
                            const duration = Number.isFinite(call.durationMillis) ? ` · ${call.durationMillis} ms` : "";
                            summary.textContent = `${call.toolName || "工具"} · ${runStatusLabel(call.status)} · ${call.authorized ? "已授权" : "未授权"}${duration}`;
                            item.append(summary);
                            if (call.inputSummary) item.append(text("pre", `输入摘要：${call.inputSummary}`, "skill-trial-output"));
                            if (call.outputSummary) item.append(text("pre", `输出摘要：${call.outputSummary}`, "skill-trial-output"));
                            if (call.errorMessage) item.append(text("p", call.errorMessage, "field-help"));
                            preview.append(item);
                        });
                    };
                    const approvalPanel = document.createElement("div"); approvalPanel.className = "skill-trial-approval";
                    const trialStorageKey = `cm-agent.skill-trial.${id}`;
                    const trialStorage = typeof window !== "undefined" ? window.sessionStorage : null;
                    let currentTrialRunId = "";
                    let approvalBusy = false;
                    let trialStateRevision = 0;
                    const refreshTrial = document.createElement("button"); refreshTrial.type = "button"; refreshTrial.className = "button ghost"; refreshTrial.textContent = "刷新试运行状态"; refreshTrial.hidden = true;
                    const renderApproval = (approval) => {
                        approvalPanel.replaceChildren();
                        if (!approval) return;
                        const ui = globalThis.CmAgentConsoleCore.approvalUiState(approval);
                        approvalPanel.append(text("h5", `高风险工具审批 · ${ui.label}`), text("p", ui.message, "field-help"));
                        if (approval.status === "PENDING") approvalPanel.append(text("p", `审批将在 ${new Date(approval.expiresAt).toLocaleString()} 前有效。`, "field-help"));
                        const decisions = [];
                        (approval.items || []).forEach((item) => {
                            const row = document.createElement("label"); row.className = "skill-approval-item";
                            const select = document.createElement("select"); select.className = "inline-select";
                            select.disabled = !ui.editable;
                            const placeholder = document.createElement("option"); placeholder.value = ""; placeholder.textContent = "请选择允许或拒绝";
                            const allow = document.createElement("option"); allow.value = "APPROVE"; allow.textContent = "允许";
                            const deny = document.createElement("option"); deny.value = "DENY"; deny.textContent = "拒绝";
                            select.append(placeholder, allow, deny);
                            select.value = item.decision || "";
                            row.append(text("span", `${item.toolName || "工具"}：${item.inputSummary || "无参数摘要"}`), select);
                            decisions.push({itemId: item.itemId, select}); approvalPanel.append(row);
                        });
                        if (!ui.editable) return;
                        const submit = document.createElement("button"); submit.type = "button"; submit.className = "button primary"; submit.textContent = "提交审批决定";
                        const approvalResult = document.createElement("p"); approvalResult.className = "form-status"; approvalResult.setAttribute("aria-live", "polite");
                        submit.addEventListener("click", async () => {
                            if (approvalBusy || !scope.isCurrent(ticket) || !globalThis.CmAgentConsoleCore.approvalUiState(approval).editable) return;
                            const items = decisions.map((entry) => ({itemId: entry.itemId, decision: entry.select.value}));
                            if (items.some((entry) => !entry.decision)) { approvalResult.textContent = "请为全部工具调用选择允许或拒绝。"; approvalResult.dataset.tone = "error"; return; }
                            approvalBusy = true; submit.disabled = true; refreshTrial.disabled = true;
                            trialStateRevision += 1;
                            decisions.forEach((entry) => { entry.select.disabled = true; });
                            approvalResult.textContent = "正在提交决定；批准不代表工具执行成功，请勿重复提交。";
                            try {
                                const resumed = await api.request(`/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(approval.runId)}/approvals/${encodeURIComponent(approval.approvalId)}/decisions`, {method: "POST", body: JSON.stringify({expectedVersion: approval.version, items})});
                                if (!scope.isCurrent(ticket)) return;
                                const updated = resumed.trial;
                                renderTrialPreview(updated);
                                result.textContent = updated.qualifiesRelease ? `试运行已通过（Run ${updated.runId.slice(0, 8)}），可以发布。` : `试运行结果：${updated.status}。`;
                                result.dataset.tone = updated.qualifiesRelease ? "success" : "neutral";
                                renderApproval(resumed.nextApproval);
                            } catch (error) {
                                if (!scope.isCurrent(ticket)) return;
                                // POST 失败可能只是响应丢失；先删除旧操作入口，再以 GET 确认结果，禁止直接重放。
                                renderApproval(null);
                                result.textContent = error.message; result.dataset.tone = "error";
                                try {
                                    await refreshCurrentTrial();
                                    if (scope.isCurrent(ticket)) { result.textContent = `${error.message} ${result.textContent}`; result.dataset.tone = "error"; }
                                }
                                catch (refreshError) { if (scope.isCurrent(ticket)) result.textContent = `${error.message} ${refreshError.message} 尚未确认结果，请刷新状态后再操作。`; }
                            } finally { approvalBusy = false; refreshTrial.disabled = false; }
                        });
                        approvalPanel.append(submit, approvalResult);
                    };
                    const loadCurrentApproval = async (tested) => {
                        const revision = ++trialStateRevision;
                        const approval = await api.request(`/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(tested.runId)}/approvals/current`);
                        if (!scope.isCurrent(ticket) || revision !== trialStateRevision) return;
                        renderApproval(approval);
                    };
                    const refreshCurrentTrial = async () => {
                        // 发布入口只对应当前展示的合格试运行；查询或新 TEST 开始后不能残留上一轮按钮。
                        publishActions.replaceChildren();
                        const revision = ++trialStateRevision;
                        const loaded = await loadTrialState((url) => api.request(url), id, currentTrialRunId);
                        if (!scope.isCurrent(ticket) || revision !== trialStateRevision) return;
                        renderTrialPreview(loaded.trial); renderApproval(loaded.approval);
                        result.textContent = `试运行结果：${trialStatusLabel(loaded.trial.status)}。`;
                        result.dataset.tone = loaded.trial.qualifiesRelease ? "success" : "neutral";
                        if (trialStorage && !["WAITING_APPROVAL", "RUNNING"].includes(loaded.trial.status)) trialStorage.removeItem(trialStorageKey);
                    };
                    refreshTrial.addEventListener("click", async () => {
                        if (approvalBusy || !currentTrialRunId || !scope.isCurrent(ticket)) return;
                        approvalBusy = true; refreshTrial.disabled = true; renderApproval(null);
                        try { await refreshCurrentTrial(); }
                        catch (error) { if (scope.isCurrent(ticket)) { result.textContent = `${error.message} 尚未确认结果，请刷新状态后再操作。`; result.dataset.tone = "error"; } }
                        finally { approvalBusy = false; refreshTrial.disabled = false; }
                    });
                    trial.append(agentLabel, agentHelp, inputLabel, run, refreshTrial, result, preview, approvalPanel, publishActions);
                    const permissions = getPermissions();
                    const loadTrialAgents = async () => {
                        if (!permissions.includes("agent:read")) {
                            agentPlaceholder.textContent = "当前账号无法读取 Agent 列表";
                            agentHelp.textContent = "选择试运行 Agent 需要 agent:read 权限；启动试运行还需要 agent:run。";
                            agentHelp.dataset.tone = "error";
                            return;
                        }
                        if (!permissions.includes("agent:run")) {
                            agentPlaceholder.textContent = "当前账号无试运行权限";
                            agentHelp.textContent = "当前账号缺少 agent:run 权限，无法启动试运行。";
                            agentHelp.dataset.tone = "error";
                            return;
                        }
                        try {
                            const agents = await api.request("/api/agents");
                            if (!scope.isCurrent(ticket)) return;
                            const available = eligibleTrialAgents(agents);
                            agentSelect.replaceChildren();
                            agentPlaceholder.textContent = available.length ? "请选择已启用的 Agent" : "没有已启用的 Agent";
                            agentSelect.append(agentPlaceholder);
                            available.forEach((agent) => {
                                const option = document.createElement("option");
                                option.value = agent.id;
                                option.textContent = `${agent.name || "未命名 Agent"} · ${agent.modelName || "未配置模型"}`;
                                agentSelect.append(option);
                            });
                            agentSelect.disabled = available.length === 0;
                            run.disabled = available.length === 0;
                            agentHelp.textContent = available.length
                                ? "试运行将使用所选 Agent 的模型配置和现有工具授权。"
                                : "请先创建并启用 Agent，再返回此处试运行。";
                        } catch (error) {
                            if (!scope.isCurrent(ticket)) return;
                            agentPlaceholder.textContent = "Agent 列表加载失败";
                            agentSelect.replaceChildren(agentPlaceholder);
                            agentSelect.disabled = true;
                            agentHelp.textContent = `无法加载 Agent 列表：${error.message}。请刷新技能详情后重试。`;
                            agentHelp.dataset.tone = "error";
                        }
                    };
                    void loadTrialAgents();
                    trial.addEventListener("submit", async (event) => {
                        event.preventDefault();
                        if (!agentSelect.value) { agentHelp.textContent = "请选择一个已启用的 Agent。"; agentHelp.dataset.tone = "error"; return; }
                        trialStateRevision += 1; renderApproval(null); publishActions.replaceChildren();
                        run.disabled = true; result.textContent = "正在执行 TEST Run…"; preview.replaceChildren();
                        try {
                            const tested = await api.request(`/api/skills/${encodeURIComponent(id)}/trials`, {method: "POST", body: JSON.stringify({versionId: summary.candidateVersionId, agentId: agentSelect.value, input: input.value.trim()})});
                            if (!scope.isCurrent(ticket)) return;
                            currentTrialRunId = tested.runId; refreshTrial.hidden = false;
                            renderTrialPreview(tested);
                            result.textContent = tested.qualifiesRelease ? `试运行已通过（Run ${tested.runId.slice(0, 8)}），可以发布。` : `试运行结果：${tested.status}。目标技能必须实际读取后才能发布。`;
                            result.dataset.tone = tested.qualifiesRelease ? "success" : "neutral";
                            if (tested.status === "WAITING_APPROVAL") {
                                if (trialStorage) trialStorage.setItem(trialStorageKey, tested.runId);
                                await loadCurrentApproval(tested);
                            } else {
                                if (trialStorage) trialStorage.removeItem(trialStorageKey);
                                renderApproval(null);
                            }
                            if (canPublishTrial(tested, summary.candidateVersionId, selectedMappingRevision)) {
                                const publish = document.createElement("button"); publish.type = "button"; publish.className = "button ghost"; publish.textContent = "发布当前候选";
                                publish.addEventListener("click", async () => {
                                    publish.disabled = true;
                                    try { await api.request(`/api/skills/${encodeURIComponent(id)}/releases`, {method: "POST", body: JSON.stringify({candidateVersionId: summary.candidateVersionId, expectedPublishedVersionId: summary.publishedVersionId || null, qualifyingTrialRunId: tested.runId})}); status("候选版本已发布；新建正式 Run 将按绑定策略解析版本。", "success"); await reload(); await select(id); }
                                    catch (error) { status(error.message, "error"); } finally { publish.disabled = false; }
                                }); publishActions.append(publish);
                            }
                        } catch (error) { result.textContent = error.message; result.dataset.tone = "error"; } finally { run.disabled = false; }
                    });
                    if (trialStorage) {
                        const previousRunId = trialStorage.getItem(trialStorageKey);
                        if (previousRunId) {
                            currentTrialRunId = previousRunId; refreshTrial.hidden = false;
                            refreshCurrentTrial().catch((error) => {
                                if (!scope.isCurrent(ticket)) return;
                                // 查询失败保留 Run 标识，刷新与断线恢复只重查，不重复发起 TEST 或旧决定。
                                result.textContent = `${error.message} 请刷新试运行状态确认结果。`; result.dataset.tone = "error";
                            });
                        }
                    }
                    if (!currentTrialRunId && permissions.includes("agent:read")) {
                        // 完成的 TEST 不依赖浏览器存储；刷新/跨页导航只查询当前主体的最新固定版本。
                        api.request(`/api/skills/${encodeURIComponent(id)}/trials/latest?versionId=${encodeURIComponent(summary.candidateVersionId)}`).then(async (latest) => {
                            if (!latest?.runId || !scope.isCurrent(ticket) || currentTrialRunId) return;
                            currentTrialRunId = latest.runId; refreshTrial.hidden = false;
                            await refreshCurrentTrial();
                        }).catch((error) => {
                            if (scope.isCurrent(ticket)) {result.textContent = `${error.message} 最近试运行读取失败，请刷新状态。`; result.dataset.tone = "error";}
                        });
                    }
                    release.append(trial);
                }
                detail.append(release);
                if (artifacts && !getPermissions().includes("skill:write") && getPermissions().includes("agent:read")) {
                    const versionId = summary.candidateVersionId || summary.publishedVersionId;
                    if (versionId) {
                        const recent = document.createElement("section"); recent.className = "detail-section"; detail.append(recent);
                        api.request(`/api/skills/${encodeURIComponent(id)}/trials/latest?versionId=${encodeURIComponent(versionId)}`).then((latest) => {
                            if (!scope.isCurrent(ticket) || !latest?.runId) return;
                            recent.append(text("h4", "最近试运行"), text("p", `试运行结果：${trialStatusLabel(latest.status)}。`, "field-help"));
                            const files = document.createElement("div"); recent.append(files);
                            artifacts.mount(files, `/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(latest.runId)}/artifacts`);
                        }).catch((error) => {if (scope.isCurrent(ticket)) recent.append(text("p", error.message, "form-status"));});
                    }
                }
                const resourceSection = document.createElement("section"); resourceSection.className = "skill-resource-section";
                resourceSection.append(text("h3", "受控资源"), text("p", `正在查看${summary.candidateVersionId ? "候选" : "正式"}版本${summary.versionNo ? ` v${summary.versionNo}` : ""}。选择资源可预览内容。`, "field-help"));
                const resourceActions = document.createElement("div"); resourceActions.className = "skill-resource-actions";
                const resources = document.createElement("div");
                resources.id = "skillResourceContent";
                const paths = [{path: "SKILL.md", mediaType: "text/markdown"}, ...(skill.resources || [])];
                paths.forEach((resource) => {
                    const button = document.createElement("button"); button.type = "button"; button.className = "skill-resource-link";
                    button.textContent = resource.path;
                    button.addEventListener("click", async () => {
                        try {
                            resourceActions.querySelectorAll("button").forEach((item) => item.classList.toggle("active", item === button));
                            const value = await api.request(`/api/skills/${encodeURIComponent(id)}/versions/${encodeURIComponent(summary.currentVersionId)}/resources?path=${encodeURIComponent(resource.path)}`);
                            resources.replaceChildren(text("pre", value.content || "", "skill-resource-preview"));
                        } catch (error) { resources.replaceChildren(text("p", error.message, "empty-state")); }
                    }); resourceActions.append(button);
                });
                resourceSection.append(resourceActions, resources); detail.append(resourceSection);
                if (getPermissions().includes("skill:write")) {
                    const actions = document.createElement("div"); actions.className = "skill-detail-actions";
                    const toggle = document.createElement("button");
                    toggle.type = "button"; toggle.className = "button ghost";
                    toggle.textContent = summary.enabled ? "停用技能" : "启用技能";
                    toggle.addEventListener("click", async () => {
                        toggle.disabled = true;
                        try {
                            await api.request(`/api/skills/${encodeURIComponent(id)}/enabled`, {
                                method: "PUT", body: JSON.stringify({enabled: !summary.enabled})
                            });
                            status(summary.enabled ? "技能已停用，后续运行不能再绑定或读取。" : "技能已启用。", "success");
                            await reload(); await select(id);
                        } catch (error) { status(error.message, "error"); }
                        finally { toggle.disabled = false; }
                    });
                    actions.append(toggle); detail.append(actions);
                }
                return ticket;
            } catch (error) { if (scope.isCurrent(ticket)) { byId("skillDetail").classList.remove("skill-detail-content"); byId("skillDetail").replaceChildren(text("p", error.message, "empty-state")); } }
        }
        async function upload(event) {
            event.preventDefault(); if (!getPermissions().includes("skill:write")) return status("没有导入技能的权限。", "error");
            if (uploadBusy) return;
            const file = byId("skillFile").files[0]; if (!file) return status("请选择 ZIP 技能包。", "error");
            const form = new FormData(); form.append("file", file, file.name);
            const ticket = scope.issue("import");
            const controls = byId("skillUploadForm").querySelectorAll("input, button");
            uploadBusy = true; controls.forEach((node) => { node.disabled = true; });
            status("正在导入技能包…");
            try {
                const created = await api.request("/api/skills", {method: "POST", body: form});
                if (!scope.isCurrent(ticket)) return;
                const listTicket = await reload();
                if (!scope.isCurrent(listTicket)) return;
                const id = (created.summary || created).id;
                const detailTicket = id ? await select(id) : listTicket;
                if (scope.isCurrent(detailTicket)) { byId("skillFile").value = ""; status("技能已导入为未启用候选；请核验、试运行并发布。", "success"); }
            } catch (error) {
                if (scope.isCurrent(ticket)) status(`${error.message}${error.code === "SKILL_CONFLICT" ? " 如需升级，请选择已有技能并使用“上传新版本”。" : ""}`, "error");
            } finally { uploadBusy = false; controls.forEach((node) => { node.disabled = !getPermissions().includes("skill:write"); }); }
        }
        return {mount() { byId("refreshSkillsBtn").addEventListener("click", async () => {
            const id = selectedId;
            const listTicket = await reload();
            if (id && scope.isCurrent(listTicket)) await select(id);
        }); byId("skillUploadForm").addEventListener("submit", upload);
            byId("skillUploadForm").querySelectorAll("input, button").forEach((node) => { node.disabled = !getPermissions().includes("skill:write"); });
            reload(); }, reload, dispose() { scope.invalidate(); selectedId = ""; }};
    }
    function preflightStatus(response) {
        return response?.check?.status || "未知";
    }
    return {canPublishTrial, createRequestScope, createSkillPage, eligibleTrialAgents, loadTrialState, preflightStatus, runStatusLabel, trialStatusLabel, versionStateLabel};
});
