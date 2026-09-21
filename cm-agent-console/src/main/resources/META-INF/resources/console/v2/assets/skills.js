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
        async function reload() {
            const ticket = scope.issue("list");
            status("正在加载技能…");
            try {
                const page = await api.request("/api/skills?page=0&size=50");
                if (!scope.isCurrent(ticket)) return;
                const items = Array.isArray(page?.items) ? page.items : Array.isArray(page) ? page : [];
                const list = byId("skillList"); list.replaceChildren();
                if (!items.length) list.append(text("p", "尚未导入技能。", "empty-state"));
                items.forEach((skill) => {
                    const button = document.createElement("button"); button.type = "button"; button.className = "resource-item";
                    button.append(text("strong", skill.name || "未命名技能"), text("span", skill.enabled ? "已启用" : "未启用"));
                    button.addEventListener("click", () => select(skill.id)); list.append(button);
                });
                status("");
            } catch (error) { if (scope.isCurrent(ticket)) status(error.message, "error"); }
        }
        async function select(id) {
            selectedId = id; const ticket = scope.issue("detail");
            byId("skillDetail").replaceChildren(text("p", "正在加载技能详情…", "empty-state"));
            try {
                const skill = await api.request(`/api/skills/${encodeURIComponent(id)}`);
                if (!scope.isCurrent(ticket)) return;
                const summary = skill.summary || skill;
                const detail = byId("skillDetail"); detail.replaceChildren(
                    text("h2", summary.name || "技能详情"), text("p", summary.description || "未提供描述"),
                    text("p", `当前版本：${summary.currentVersionNo || "—"}　状态：${summary.enabled ? "已启用" : "未启用"}`)
                );
                const resources = document.createElement("div");
                resources.id = "skillResourceContent";
                const paths = [{path: "SKILL.md", mediaType: "text/markdown"}, ...(skill.resources || [])];
                paths.forEach((resource) => {
                    const button = document.createElement("button"); button.type = "button"; button.className = "text-link";
                    button.textContent = resource.path;
                    button.addEventListener("click", async () => {
                        try {
                            const value = await api.request(`/api/skills/${encodeURIComponent(id)}/versions/${encodeURIComponent(summary.currentVersionId)}/resources?path=${encodeURIComponent(resource.path)}`);
                            resources.replaceChildren(text("pre", value.content || "", "skill-resource-preview"));
                        } catch (error) { resources.replaceChildren(text("p", error.message, "empty-state")); }
                    }); detail.append(button);
                });
                detail.append(resources);
                if (getPermissions().includes("skill:write")) {
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
                    detail.append(toggle);
                }
            } catch (error) { if (scope.isCurrent(ticket)) byId("skillDetail").replaceChildren(text("p", error.message, "empty-state")); }
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
