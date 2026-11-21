(function (root, factory) {
    const exported = factory();
    if (typeof module === "object" && module.exports) module.exports = exported;
    else root.CmAgentV3Chat = exported.createWorkspaceAdapter({document: root.document, host: root});
})(typeof globalThis === "object" ? globalThis : this, function () {
    "use strict";

    /**
     * 只调整真实业务节点的位置，不持有认证、正文、审批参数或请求状态。
     * 占位节点让借出的控件在换项目、退出与跨页前同步回到原实例，
     * 既保留原事件监听，也让旧业务模块能按原有生命周期清理敏感内容。
     */
    function createWorkspaceAdapter({document, host = globalThis} = {}) {
        const $ = id => document?.getElementById(id);
        let active = false, observer = null, selectedTab = "settings", pendingBefore = false;
        const borrowed = [], projectBorrowed = [], listeners = [], created = [];
        let groups = null, tabs = null, ordinaryNotice = null, projectNotice = null;

        function listen(node, name, action) {
            if (!node) return;
            node.addEventListener(name, action);
            listeners.push(() => node.removeEventListener(name, action));
        }
        function node(tag, text, className) {
            const value = document.createElement(tag);
            if (text) value.textContent = text;
            if (className) value.className = className;
            return value;
        }
        function hide(value, hidden) {
            if (value && value.hidden !== hidden) value.hidden = hidden;
        }
        function borrow(value, target, records = borrowed) {
            if (!value || !target || !value.parentNode || records.some(record => record.value === value)) return;
            const marker = document.createComment("V3 工作区控件原位置");
            value.parentNode.insertBefore(marker, value);
            records.push({value, marker});
            target.append(value);
        }
        function restore(records) {
            for (const {value, marker} of records.splice(0).reverse()) {
                if (marker.parentNode) marker.parentNode.insertBefore(value, marker);
                else value.remove();
                marker.remove();
            }
        }
        function section(id, title) {
            const value = node("section", "", "v3-chat-context-section");
            value.id = id;
            if (title) value.append(node("h3", title));
            $("v3ContextBody").append(value);
            created.push(value);
            return value;
        }
        function isProject() { return Boolean($("projectChatWorkspace") && !$("projectChatWorkspace").hidden); }
        function showTab(id, open) {
            selectedTab = id;
            sync();
            if (open) host.CmAgentV3?.openContext?.();
        }
        function projectReady() { return Boolean($("projectDevelopment") && !$("projectDevelopment").hidden); }
        function projectBodyReady() { return projectReady() && Boolean($("projectChatBody") && !$("projectChatBody").hidden); }
        function hasPending(project) {
            if (project) return Boolean(projectBodyReady() && $("projectApprovalItems")?.querySelector("button"));
            return Boolean($("chatApprovalRegion") && !$("chatApprovalRegion").hidden);
        }
        function sync() {
            if (!active || !groups) return;
            const project = isProject(), ready = projectBodyReady();
            // 原模块控制业务节点的 hidden；适配层只镜像到自己拥有的布局包装层。
            hide($("v3ProjectSelectorNavigation"), !project);
            hide($("v3ProjectConversationNavigation"), !project || !projectReady());
            hide(groups.settings, selectedTab !== "settings" || project);
            hide(groups.projectSettings, selectedTab !== "settings" || !project);
            hide(groups.approvals, selectedTab !== "approvals" || project);
            hide(groups.projectApprovals, selectedTab !== "approvals" || !project || !ready);
            hide(groups.runs, selectedTab !== "runs");
            hide($("v3ProjectOperationsContext"), !project || !ready);
            for (const button of tabs?.children || []) {
                const pressed = String(button.dataset.contextTab === selectedTab);
                if (button.getAttribute("aria-pressed") !== pressed) button.setAttribute("aria-pressed", pressed);
            }
            const pending = hasPending(project);
            hide(ordinaryNotice, project || !pending);
            hide(projectNotice, !project || !pending);
            const toggle = $("v3ContextToggle");
            if (toggle) {
                const label = pending ? "上下文，存在待审批操作" : "上下文";
                if (toggle.getAttribute("aria-label") !== label) toggle.setAttribute("aria-label", label);
            }
            if (pending && !pendingBefore) {
                selectedTab = "approvals";
                pendingBefore = true;
                sync();
                host.CmAgentV3?.openContext?.();
            } else pendingBefore = pending;
        }
        function makeNotice(parent) {
            if (!parent) return null;
            const notice = node("button", "有待审批操作，打开上下文核对", "v3-approval-notice");
            notice.type = "button";
            notice.hidden = true;
            notice.dataset.v3ApprovalNotice = "";
            parent.insertBefore(notice, parent.firstChild);
            created.push(notice);
            return notice;
        }
        function makeContext() {
            tabs = $("v3ContextTabs");
            if (!tabs || !$("v3ContextBody")) return;
            for (const [id, text] of [["settings", "资料与权限"], ["approvals", "审批"], ["runs", "运行"]]) {
                const button = node("button", text, "v3-context-tab");
                button.type = "button";
                button.dataset.contextTab = id;
                button.setAttribute("aria-pressed", String(id === selectedTab));
                listen(button, "click", () => showTab(id, false));
                tabs.append(button);
                created.push(button);
            }
            groups = {
                settings: section("v3OrdinarySettings", "本轮资料与本机授权"),
                projectSettings: section("v3ProjectSettings", "项目资料"),
                approvals: section("v3OrdinaryApprovals", "操作审批"),
                projectApprovals: section("v3ProjectApprovals", ""),
                runs: section("v3ChatRuns", "运行记录")
            };
            const projectLink = node("a", "打开项目文件与搜索", "button ghost");
            projectLink.href = "/console/v3/projects.html";
            groups.projectSettings.append(node("p", "项目文件、搜索、文本预览与文件引用使用本地项目页的真实受控接口。", "field-help"), projectLink);
            const runLink = node("a", "查看完整运行记录", "button ghost");
            runLink.href = "/console/v3/runs.html";
            groups.runs.append(node("p", "运行状态与停止操作保留在当前会话；完整输出、工具调用与错误编号可在运行记录查看。", "field-help"), runLink);
        }
        function restoreProject() {
            restore(projectBorrowed);
            if (projectNotice) {
                const index = created.indexOf(projectNotice);
                if (index >= 0) created.splice(index, 1);
                projectNotice.remove();
                projectNotice = null;
            }
            pendingBefore = false;
        }
        const layoutHooks = {
            beforeProjectReplace() { restoreProject(); },
            afterProjectClone() {
                if (!active || !groups) return;
                borrow($("v3ProjectConversationNavigation"), $("v3WorkspaceNavigation"), projectBorrowed);
                borrow($("v3ProjectApprovalContext"), groups.projectApprovals, projectBorrowed);
                borrow($("v3ProjectOperationsContext"), groups.runs, projectBorrowed);
                projectNotice = makeNotice($("projectChatBody"));
                sync();
            },
            beforeDispose() { restoreProject(); }
        };
        return {
            layoutHooks,
            mount() {
                if (active || document?.body?.dataset.consoleVersion !== "v3" || document.body.dataset.page !== "chatPage"
                        || !$("v3WorkspaceNavigation") || !$("v3ContextBody")) return;
                active = true;
                makeContext();
                borrow($("chatModeSwitch"), $("v3WorkspaceNavigation"));
                borrow($("chatSessionPanel"), $("v3WorkspaceNavigation"));
                borrow($("v3ProjectSelectorNavigation"), $("v3WorkspaceNavigation"));
                if (groups) {
                    borrow($("v3OrdinaryClientContext"), groups.settings);
                    borrow($("v3OrdinaryWorkspaceContext"), groups.settings);
                    borrow($("chatApprovalRegion"), groups.approvals);
                    borrow($("chatApprovalHistoryRegion"), groups.approvals);
                }
                ordinaryNotice = makeNotice($("ordinaryChatMain"));
                // 使用页面级委托，项目切换移除提示后不在监听列表中继续持有旧模板节点。
                listen($("chatPage"), "click", event => {
                    if (event.target?.closest?.("[data-v3-approval-notice]")) showTab("approvals", true);
                });
                listen($("chatSessionsToggle"), "click", () => host.CmAgentV3?.openNavigation?.());
                listen($("v3NewConversation"), "click", event => {
                    event.preventDefault();
                    const project = isProject();
                    const action = $(project ? "projectNewConversation" : "newChatConversationBtn");
                    if (!action || action.disabled || action.hidden) {
                        if (project) {
                            const agent = $("projectChatAgent");
                            (agent && !agent.hidden && !agent.disabled ? agent : $("chatProjectSelect"))?.focus();
                        } else $("chatAgentSelect")?.focus();
                        return;
                    }
                    action.click();
                    host.CmAgentV3?.closeNavigation?.();
                });
                listen($("v3FindConversation"), "click", event => {
                    event.preventDefault();
                    host.CmAgentV3?.openNavigation?.();
                    $(isProject() ? "chatProjectSelect" : "chatConversationSearch")?.focus();
                });
                listen($("v3WorkspaceNavigation"), "click", event => {
                    if (event.target?.closest?.("#chatConversationList button, #projectConversationList button")) host.CmAgentV3?.closeNavigation?.();
                });
                // 观察真实模块更新的可见性与审批节点；不参与请求，也不依据正文推断审批结果。
                if (host.MutationObserver) {
                    observer = new host.MutationObserver(sync);
                    observer.observe(document.body, {subtree: true, childList: true, attributes: true, attributeFilter: ["hidden"]});
                }
                if ($("v3ProjectConversationNavigation")) layoutHooks.afterProjectClone();
                sync();
            },
            dispose() {
                if (!active) return;
                active = false;
                observer?.disconnect(); observer = null;
                listeners.splice(0).forEach(remove => remove());
                restoreProject();
                restore(borrowed);
                created.splice(0).forEach(value => value.remove());
                groups = null; tabs = null; ordinaryNotice = null; selectedTab = "settings"; pendingBefore = false;
            }
        };
    }
    return {createWorkspaceAdapter};
});
