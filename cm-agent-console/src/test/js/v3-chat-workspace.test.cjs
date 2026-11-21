const test = require("node:test");
const assert = require("node:assert/strict");

// 只替换布局边界所需的 DOM；真实适配模块负责移动节点，测试不重写其布局算法。
function fixture({version = "v3", page = "chatPage"} = {}) {
    const observers = [];
    class Element {
        constructor(tagName = "div", nodeType = 1) {
            this.tagName = tagName.toUpperCase(); this.nodeType = nodeType; this.parentNode = null;
            this.childNodes = []; this.attributes = new Map(); this.dataset = {}; this.listeners = new Map();
            this.style = {setProperty(name, value) {this[name] = value;}, removeProperty(name) {delete this[name];}}; this._hidden = false; this._text = "";
            this.classList = {
                add: (...names) => names.forEach(name => this.classes.add(name)),
                remove: (...names) => names.forEach(name => this.classes.delete(name)),
                contains: name => this.classes.has(name),
                toggle: (name, force) => { const next = force === undefined ? !this.classes.has(name) : force; if (next) this.classes.add(name); else this.classes.delete(name); return next; }
            };
            this.classes = new Set();
        }
        get ownerDocument() { return document; }
        get children() { return this.childNodes.filter(node => node.nodeType === 1); }
        get firstChild() { return this.childNodes[0] || null; }
        get nextSibling() { const siblings = this.parentNode?.childNodes || []; return siblings[siblings.indexOf(this) + 1] || null; }
        get parentElement() { return this.parentNode?.nodeType === 1 ? this.parentNode : null; }
        get isConnected() { return document.body === this || document.body.contains(this); }
        get id() { return this.attributes.get("id") || ""; }
        set id(value) { this.attributes.set("id", value); }
        get className() { return [...this.classes].join(" "); }
        set className(value) { this.classes = new Set(String(value).split(/\s+/).filter(Boolean)); }
        get hidden() { return this._hidden; }
        set hidden(value) { this._hidden = Boolean(value); }
        get textContent() { return this._text + this.childNodes.map(node => node.textContent).join(""); }
        set textContent(value) { this._text = String(value); this.replaceChildren(); }
        append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
        appendChild(node) { node.remove(); node.parentNode = this; this.childNodes.push(node); return node; }
        prepend(...nodes) { for (const node of [...nodes].reverse()) this.insertBefore(node, this.firstChild); }
        insertBefore(node, reference) {
            if (reference == null) return this.appendChild(node);
            const index = this.childNodes.indexOf(reference);
            assert.notEqual(index, -1, "恢复位置必须属于原容器");
            node.remove(); node.parentNode = this; this.childNodes.splice(index, 0, node); return node;
        }
        removeChild(node) { const index = this.childNodes.indexOf(node); if (index !== -1) { this.childNodes.splice(index, 1); node.parentNode = null; } return node; }
        replaceChildren(...nodes) { for (const node of this.childNodes) node.parentNode = null; this.childNodes = []; this.append(...nodes); }
        remove() { this.parentNode?.removeChild(this); }
        replaceWith(...nodes) { const parent = this.parentNode; if (!parent) return; for (const node of nodes) parent.insertBefore(node, this); this.remove(); }
        before(...nodes) { const parent = this.parentNode; if (!parent) return; for (const node of nodes) parent.insertBefore(node, this); }
        after(...nodes) { const parent = this.parentNode; if (!parent) return; const next = this.nextSibling; for (const node of nodes) parent.insertBefore(node, next); }
        contains(node) { return this.childNodes.some(child => child === node || child.contains(node)); }
        setAttribute(name, value) {
            this.attributes.set(name, String(value));
            if (name === "class") this.className = value;
            if (name === "hidden") this.hidden = true;
            if (name.startsWith("data-")) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, character) => character.toUpperCase())] = String(value);
        }
        getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
        removeAttribute(name) { this.attributes.delete(name); if (name === "hidden") this.hidden = false; }
        hasAttribute(name) { return this.attributes.has(name) || name === "hidden" && this.hidden; }
        matches(selector) {
            const choices = selector.split(",").map(value => value.trim());
            return choices.some(choice => {
                const last = choice.split(/\s+/).at(-1);
                const attributes = [...last.matchAll(/\[([^=\]]+)(?:=["']?([^\]"']+)["']?)?\]/g)];
                if (!attributes.every(([, name, value]) => value === undefined ? this.hasAttribute(name) : this.getAttribute(name) === value)) return false;
                const simple = last.replace(/\[[^\]]+\]/g, "");
                const id = simple.match(/#([\w-]+)/)?.[1]; if (id && this.id !== id) return false;
                if (![...simple.matchAll(/\.([\w-]+)/g)].every(([, name]) => this.classes.has(name))) return false;
                const tag = simple.match(/^[a-z][\w-]*/i)?.[0]; return !tag || this.tagName === tag.toUpperCase();
            });
        }
        closest(selector) { for (let node = this; node; node = node.parentNode) if (node.matches(selector)) return node; return null; }
        querySelectorAll(selector) { const matches = []; for (const child of this.childNodes) { if (child.nodeType === 1 && child.matches(selector)) matches.push(child); matches.push(...child.querySelectorAll(selector)); } return matches; }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        addEventListener(type, listener, options) {
            const callbacks = this.listeners.get(type) || []; callbacks.push(listener); this.listeners.set(type, callbacks);
            options?.signal?.addEventListener("abort", () => this.removeEventListener(type, listener), {once: true});
        }
        removeEventListener(type, listener) { this.listeners.set(type, (this.listeners.get(type) || []).filter(current => current !== listener)); }
        dispatchEvent(event) { for (const listener of this.listeners.get(event.type) || []) listener({...event, target: event.target || this}); }
        focus() { document.activeElement = this; }
        getClientRects() { return this.hidden ? [] : [{width: 100, height: 20}]; }
        cloneNode() { throw new Error("布局适配不能复制真实业务节点"); }
    }
    const document = {
        body: null, activeElement: null,
        createElement(tagName) { return new Element(tagName); },
        createComment(text) { const node = new Element("comment", 8); node._text = text; return node; },
        getElementById(id) { return this.body.id === id ? this.body : this.body.querySelector("#" + id); },
        querySelector(selector) { return this.body.matches(selector) ? this.body : this.body.querySelector(selector); },
        querySelectorAll(selector) { return this.body.querySelectorAll(selector); },
        addEventListener() {}, removeEventListener() {}
    };
    document.body = new Element("body");
    document.body.setAttribute("data-console-version", version); document.body.setAttribute("data-page", page);
    const add = (id, parent = document.body, tag = "div") => { const node = document.createElement(tag); node.id = id; parent.appendChild(node); return node; };
    const sidebar = add("v3WorkspaceNavigation"), contextPanel = add("v3ContextPanel");
    add("v3ContextTabs", contextPanel);
    const context = add("v3ContextBody", contextPanel), pageNode = add("chatPage");
    const ordinary = add("ordinaryChatMain", pageNode), sessions = add("chatSessionPanel", pageNode);
    const modeSwitch = add("chatModeSwitch", pageNode); modeSwitch.hidden = true;
    const client = add("v3OrdinaryClientContext", ordinary), workspace = add("v3OrdinaryWorkspaceContext", ordinary);
    const approval = add("chatApprovalRegion", ordinary), history = add("chatApprovalHistoryRegion", ordinary);
    approval.hidden = true; history.hidden = true;
    add("chatApprovalList", approval); add("chatApprovalHistoryList", history);
    add("chatConversationTitle", ordinary).textContent = "普通会话";
    add("chatLiveStatus", ordinary).textContent = "已就绪";
    const projectWorkspace = add("projectChatWorkspace", pageNode); projectWorkspace.hidden = true;
    const selector = add("v3ProjectSelectorNavigation", projectWorkspace);
    const projectMount = add("chatProjectConversationMount", projectWorkspace);
    class MutationObserver {
        constructor(callback) { this.callback = callback; this.active = false; this.observations = []; observers.push(this); }
        observe(target, options) { this.active = true; this.observations.push({target, options}); }
        disconnect() { this.active = false; }
    }
    const host = {document, MutationObserver, AbortController, Event: class {constructor(type) {this.type = type;}},
        setTimeout: callback => { callback(); return 1; }, clearTimeout() {}, requestAnimationFrame: callback => { callback(); return 1; }, cancelAnimationFrame() {}};
    return {document, host, add, sidebar, context, pageNode, ordinary, sessions, modeSwitch, client, workspace, approval, history, selector, projectWorkspace, projectMount, observers,
        flush() { for (const observer of observers) if (observer.active) observer.callback([{type: "attributes", attributeName: "hidden", target: projectWorkspace}]); }};
}

function adapterFor(dom) {
    const module = require("../../main/resources/META-INF/resources/console/v3/assets/chat-workspace.js");
    assert.equal(typeof module.createWorkspaceAdapter, "function", "布局模块须提供可隔离的实例工厂");
    return module.createWorkspaceAdapter({document: dom.document, host: dom.host});
}

function projectNodes(dom, label) {
    const root = dom.add("projectDevelopment", dom.projectMount);
    const navigation = dom.add("v3ProjectConversationNavigation", root);
    dom.add("projectDevelopmentHeading", navigation).textContent = label;
    dom.add("projectChatAgent", navigation);
    dom.add("projectConversationList", navigation);
    const body = dom.add("projectChatBody", root); body.hidden = true;
    dom.add("projectChatTitle", body).textContent = label + "会话";
    dom.add("projectRunStatus", body);
    const approval = dom.add("v3ProjectApprovalContext", body);
    dom.add("projectApprovalItems", approval);
    const operations = dom.add("v3ProjectOperationsContext", body);
    dom.add("projectCommandResults", operations);
    return {root, navigation, body, approval, operations};
}

test("V3 布局移动真实普通会话节点，保留身份、事件与业务 hidden 状态", () => {
    const dom = fixture(), adapter = adapterFor(dom);
    let clicks = 0; dom.sessions.addEventListener("click", () => clicks++);
    adapter.mount();
    assert.equal(dom.document.getElementById("chatSessionPanel"), dom.sessions);
    assert.ok(dom.sidebar.contains(dom.sessions));
    assert.ok(dom.sidebar.contains(dom.modeSwitch));
    assert.ok(dom.sidebar.contains(dom.selector));
    for (const node of [dom.client, dom.workspace, dom.approval, dom.history]) assert.ok(dom.context.contains(node));
    dom.sessions.dispatchEvent({type: "click"}); assert.equal(clicks, 1);
    assert.equal(dom.modeSwitch.hidden, true);
    assert.equal(dom.approval.hidden, true);
    assert.equal(dom.history.hidden, true);
    dom.sessions.hidden = true; dom.projectWorkspace.hidden = false; dom.flush();
    assert.equal(dom.sessions.hidden, true, "项目模式由共享业务逻辑决定会话导航是否可见");
    assert.equal(dom.projectWorkspace.hidden, false);
    adapter.dispose();
});

test("项目未选定时新会话保留抽屉并聚焦选择器", () => {
    const dom = fixture();
    const action = dom.add("v3NewConversation", dom.sidebar, "a");
    const selector = dom.add("chatProjectSelect", dom.selector, "select");
    let closeCount = 0;
    dom.host.CmAgentV3 = {closeNavigation: () => closeCount++};
    dom.projectWorkspace.hidden = false;
    const adapter = adapterFor(dom);
    adapter.mount();
    action.dispatchEvent({type: "click", preventDefault() {}});
    assert.equal(dom.document.activeElement, selector);
    assert.equal(closeCount, 0);
    adapter.dispose();
});

test("切换项目先还原被移出的导航和右栏，再克隆新项目，避免重复 ID", () => {
    const dom = fixture(), adapter = adapterFor(dom); adapter.mount();
    const first = projectNodes(dom, "项目甲");
    adapter.layoutHooks.afterProjectClone({mount: dom.projectMount, project: {id: "a"}});
    assert.ok(dom.sidebar.contains(first.navigation));
    assert.ok(dom.context.contains(first.approval));
    assert.ok(dom.context.contains(first.operations));
    assert.equal(first.body.hidden, true);
    adapter.layoutHooks.beforeProjectReplace({mount: dom.projectMount, projectId: "b"});
    assert.equal(first.navigation.parentNode, first.root);
    assert.equal(first.approval.parentNode, first.body);
    assert.equal(first.operations.parentNode, first.body);
    dom.projectMount.replaceChildren();
    const second = projectNodes(dom, "项目乙");
    adapter.layoutHooks.afterProjectClone({mount: dom.projectMount, project: {id: "b"}});
    for (const id of ["v3ProjectConversationNavigation", "projectChatAgent", "v3ProjectApprovalContext", "projectCommandResults"]) {
        assert.equal(dom.document.querySelectorAll("#" + id).length, 1, `${id} 必须只属于当前项目`);
    }
    assert.equal(dom.document.getElementById("v3ProjectConversationNavigation"), second.navigation);
    assert.equal(first.navigation.isConnected, false);
    adapter.dispose();
});

test("离页释放监听与观察器并按原顺序还原普通和项目节点", () => {
    const dom = fixture(), adapter = adapterFor(dom);
    const ordinaryOrder = [...dom.ordinary.children], pageOrder = [...dom.pageNode.children];
    adapter.mount(); const project = projectNodes(dom, "当前项目");
    adapter.layoutHooks.afterProjectClone({mount: dom.projectMount, project: {id: "current"}});
    assert.ok(dom.observers.some(observer => observer.active));
    adapter.dispose();
    assert.deepEqual(dom.ordinary.children, ordinaryOrder);
    assert.deepEqual(dom.pageNode.children, pageOrder);
    assert.equal(project.navigation.parentNode, project.root);
    assert.equal(project.approval.parentNode, project.body);
    assert.equal(project.operations.parentNode, project.body);
    assert.ok(dom.observers.every(observer => !observer.active));
    adapter.dispose();
    assert.deepEqual(dom.ordinary.children, ordinaryOrder, "重复释放不得再次移动或删除业务节点");
});

test("项目组件销毁钩子同步收回外移节点，不依赖异步观察器", () => {
    const dom = fixture(), adapter = adapterFor(dom); adapter.mount();
    const project = projectNodes(dom, "当前项目");
    adapter.layoutHooks.afterProjectClone({mount: dom.projectMount, project: {id: "current"}});
    adapter.layoutHooks.beforeDispose();
    assert.equal(project.navigation.parentNode, project.root);
    assert.equal(project.approval.parentNode, project.body);
    assert.equal(project.operations.parentNode, project.body);
    assert.equal(dom.document.querySelectorAll("#projectChatAgent").length, 1);
    adapter.dispose();
});

test("V2 或非聊天页不启动 V3 聊天布局，也不持有观察器", () => {
    for (const options of [{version: "v2"}, {page: "agentsPage"}]) {
        const dom = fixture(options), adapter = adapterFor(dom);
        adapter.mount();
        assert.equal(dom.sessions.parentNode, dom.pageNode);
        assert.equal(dom.client.parentNode, dom.ordinary);
        assert.ok(dom.observers.every(observer => !observer.active));
        adapter.dispose();
    }
});

test("离页后迟到的项目克隆钩子不能重新占用侧栏或上下文", () => {
    const dom = fixture(), adapter = adapterFor(dom); adapter.mount(); adapter.dispose();
    const project = projectNodes(dom, "迟到项目");
    adapter.layoutHooks.afterProjectClone({mount: dom.projectMount, project: {id: "late"}});
    dom.flush();
    assert.equal(project.navigation.parentNode, project.root);
    assert.equal(project.approval.parentNode, project.body);
    assert.equal(project.operations.parentNode, project.body);
    assert.equal(dom.sidebar.contains(project.navigation), false);
    assert.equal(dom.context.contains(project.approval), false);
});
