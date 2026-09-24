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

    function createSkillPage({api, getSessionEpoch, getPermissions, document}) {
        const scope = createRequestScope(getSessionEpoch);
        let selectedId = "";
        let selectedMappingRevision = null;
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
                    empty.append(text("strong", "尚未导入技能"), text("p", "从右侧导入受控 ZIP 包后，可在这里核验并启用。"));
                    list.append(empty);
                }
                items.forEach((skill) => {
                    const button = document.createElement("button"); button.type = "button"; button.className = "resource-item skill-resource-item";
                    button.dataset.skillId = skill.id || ""; button.setAttribute("aria-pressed", "false");
                    const heading = document.createElement("span"); heading.className = "skill-resource-heading";
                    heading.append(text("strong", skill.name || "未命名技能"), statusBadge(skill.enabled));
                    button.append(heading, text("span", skill.description || "未提供技能说明", "skill-resource-description"));
                    button.addEventListener("click", () => select(skill.id)); list.append(button);
                });
                status("");
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
                metadata.append(text("dt", "当前版本"), text("dd", `v${summary.currentVersionNo || "—"}`), text("dt", "使用状态"), text("dd", summary.enabled ? "可绑定到 Agent" : "停用后不可绑定或读取"));
                detail.append(header, metadata);
                const release = document.createElement("section"); release.className = "skill-release-workspace";
                release.append(text("h3", "发布工作区"), text("p", "按“依赖预检 → 指定版本试运行 → 人工发布”推进；候选不会进入正式运行。", "field-help"));
                const pointers = document.createElement("dl"); pointers.className = "skill-release-pointers";
                pointers.append(text("dt", "候选版本"), text("dd", summary.candidateVersionId ? summary.candidateVersionId.slice(0, 8) : "当前没有候选"), text("dt", "正式版本"), text("dd", summary.publishedVersionId ? summary.publishedVersionId.slice(0, 8) : "尚未发布"));
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
                const history = document.createElement("section"); history.className = "skill-version-history";
                history.append(text("h4", "版本历史与回滚"), text("p", "回滚会重新发布历史版本，只影响之后按跟随策略创建的 Run。", "field-help"));
                const historyList = document.createElement("ul"); historyList.className = "skill-dependency-list";
                history.append(historyList); release.append(history);
                try {
                    const versions = await api.request(`/api/skills/${encodeURIComponent(id)}/versions`);
                    if (!scope.isCurrent(ticket)) return;
                    if (!versions.length) historyList.append(text("li", "尚无版本历史。"));
                    versions.forEach((version) => {
                        const row = document.createElement("li");
                        row.append(text("span", `v${version.versionNo} · ${version.state}`));
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
                } catch (error) { historyList.append(text("li", `版本历史读取失败：${error.message}`)); }
                if (summary.candidateVersionId && getPermissions().includes("skill:write")) {
                    const preflight = document.createElement("div"); preflight.className = "skill-preflight-actions";
                    const check = document.createElement("button"); check.type = "button"; check.className = "button ghost"; check.textContent = "执行候选结构预检";
                    const result = document.createElement("p"); result.className = "form-status"; result.setAttribute("aria-live", "polite");
                    check.addEventListener("click", async () => {
                        check.disabled = true; result.textContent = "正在验证候选版本依赖…";
                        try {
                            const checked = await api.request(`/api/skills/${encodeURIComponent(id)}/preflights?versionId=${encodeURIComponent(summary.candidateVersionId)}&scope=STRUCTURAL`, {method: "POST"});
                            result.textContent = checked.summary?.status === "PASSED" ? "结构预检通过。" : `结构预检结果：${checked.summary?.status || "未知"}。请修复失败项后重新执行。`;
                            result.dataset.tone = checked.summary?.status === "PASSED" ? "success" : "neutral";
                        } catch (error) { result.textContent = error.message; result.dataset.tone = "error"; } finally { check.disabled = false; }
                    });
                    preflight.append(check, result); release.append(preflight);
                }
                if (summary.candidateVersionId && getPermissions().includes("skill:write")) {
                    const trial = document.createElement("form"); trial.className = "skill-trial-form";
                    trial.append(text("h4", "指定版本试运行"), text("p", "使用真实 Agent、模型和工具治理链路；工具可能产生真实副作用。", "field-help"));
                    const agentLabel = text("label", "目标 Agent ID"); const agentInput = document.createElement("input"); agentInput.required = true; agentInput.placeholder = "输入要验证的 Agent UUID"; agentLabel.append(agentInput);
                    const inputLabel = text("label", "测试输入"); const input = document.createElement("textarea"); input.required = true; input.placeholder = "描述希望模型使用此技能完成的任务"; inputLabel.append(input);
                    const run = document.createElement("button"); run.type = "submit"; run.className = "button primary"; run.textContent = "开始试运行";
                    const result = document.createElement("p"); result.className = "form-status"; result.setAttribute("aria-live", "polite");
                    const approvalPanel = document.createElement("div"); approvalPanel.className = "skill-trial-approval";
                    const trialStorageKey = `cm-agent.skill-trial.${id}`;
                    const trialStorage = typeof window !== "undefined" ? window.sessionStorage : null;
                    const renderApproval = (approval) => {
                        approvalPanel.replaceChildren();
                        if (!approval) return;
                        approvalPanel.append(text("h5", "高风险工具审批"), text("p", `试运行暂停，审批将在 ${new Date(approval.expiresAt).toLocaleString()} 前有效。`, "field-help"));
                        const decisions = [];
                        (approval.items || []).forEach((item) => {
                            const row = document.createElement("label"); row.className = "skill-approval-item";
                            const select = document.createElement("select"); select.className = "inline-select";
                            const placeholder = document.createElement("option"); placeholder.value = ""; placeholder.textContent = "请选择允许或拒绝";
                            const allow = document.createElement("option"); allow.value = "APPROVE"; allow.textContent = "允许";
                            const deny = document.createElement("option"); deny.value = "DENY"; deny.textContent = "拒绝";
                            select.append(placeholder, allow, deny);
                            row.append(text("span", `${item.toolName || "工具"}：${item.inputSummary || "无参数摘要"}`), select);
                            decisions.push({itemId: item.itemId, select}); approvalPanel.append(row);
                        });
                        if (!approval.canDecide) {
                            approvalPanel.append(text("p", "当前账号只能查看审批，需由试运行发起人完成决定。", "field-help"));
                            return;
                        }
                        const submit = document.createElement("button"); submit.type = "button"; submit.className = "button primary"; submit.textContent = "提交审批决定";
                        const approvalResult = document.createElement("p"); approvalResult.className = "form-status"; approvalResult.setAttribute("aria-live", "polite");
                        submit.addEventListener("click", async () => {
                            const items = decisions.map((entry) => ({itemId: entry.itemId, decision: entry.select.value}));
                            if (items.some((entry) => !entry.decision)) { approvalResult.textContent = "请为全部工具调用选择允许或拒绝。"; approvalResult.dataset.tone = "error"; return; }
                            submit.disabled = true; approvalResult.textContent = "正在恢复试运行…";
                            try {
                                const resumed = await api.request(`/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(approval.runId)}/approvals/${encodeURIComponent(approval.approvalId)}/decisions`, {method: "POST", body: JSON.stringify({expectedVersion: approval.version, items})});
                                const updated = resumed.trial;
                                result.textContent = updated.qualifiesRelease ? `试运行已通过（Run ${updated.runId.slice(0, 8)}），可以发布。` : `试运行结果：${updated.status}。`;
                                result.dataset.tone = updated.qualifiesRelease ? "success" : "neutral";
                                renderApproval(resumed.nextApproval);
                            } catch (error) { approvalResult.textContent = error.message; approvalResult.dataset.tone = "error"; } finally { submit.disabled = false; }
                        });
                        approvalPanel.append(submit, approvalResult);
                    };
                    const loadCurrentApproval = async (tested) => {
                        const approval = await api.request(`/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(tested.runId)}/approvals/current`);
                        renderApproval(approval);
                    };
                    trial.append(agentLabel, inputLabel, run, result, approvalPanel);
                    trial.addEventListener("submit", async (event) => {
                        event.preventDefault(); run.disabled = true; result.textContent = "正在执行 TEST Run…";
                        try {
                            const tested = await api.request(`/api/skills/${encodeURIComponent(id)}/trials`, {method: "POST", body: JSON.stringify({versionId: summary.candidateVersionId, agentId: agentInput.value.trim(), input: input.value.trim()})});
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
                                }); trial.append(publish);
                            }
                        } catch (error) { result.textContent = error.message; result.dataset.tone = "error"; } finally { run.disabled = false; }
                    });
                    if (trialStorage) {
                        const previousRunId = trialStorage.getItem(trialStorageKey);
                        if (previousRunId) {
                            api.request(`/api/skills/${encodeURIComponent(id)}/trials/${encodeURIComponent(previousRunId)}`)
                                .then((previous) => previous.status === "WAITING_APPROVAL" ? loadCurrentApproval(previous) : trialStorage.removeItem(trialStorageKey))
                                .catch(() => trialStorage.removeItem(trialStorageKey));
                        }
                    }
                    release.append(trial);
                }
                detail.append(release);
                const resourceSection = document.createElement("section"); resourceSection.className = "skill-resource-section";
                resourceSection.append(text("h3", "受控资源"), text("p", "选择资源可在下方预览当前版本内容。", "field-help"));
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
            } catch (error) { if (scope.isCurrent(ticket)) { byId("skillDetail").classList.remove("skill-detail-content"); byId("skillDetail").replaceChildren(text("p", error.message, "empty-state")); } }
        }
        async function upload(event) {
            event.preventDefault(); if (!getPermissions().includes("skill:write")) return status("没有导入技能的权限。", "error");
            const file = byId("skillFile").files[0]; if (!file) return status("请选择 ZIP 技能包。", "error");
            const form = new FormData(); form.append("file", file, file.name);
            status("正在导入技能包…");
            try { await api.request("/api/skills", {method: "POST", body: form}); status("技能已导入，初始状态为未启用。", "success"); await reload(); }
            catch (error) { status(error.message, "error"); }
        }
        return {mount() { byId("refreshSkillsBtn").addEventListener("click", reload); byId("skillUploadForm").addEventListener("submit", upload); reload(); }, reload, dispose() { scope.invalidate(); selectedId = ""; }};
    }
    return {canPublishTrial, createRequestScope, createSkillPage};
});
