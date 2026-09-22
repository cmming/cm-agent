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

    function createSkillPage({api, getSessionEpoch, getPermissions, document}) {
        const scope = createRequestScope(getSessionEpoch);
        let selectedId = "";
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
            selectedId = id; selectListItem(id); const ticket = scope.issue("detail");
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
    return {createRequestScope, createSkillPage};
});
