const test = require("node:test");
const assert = require("node:assert/strict");
const projects = require("../../main/resources/META-INF/resources/console/v2/assets/local-projects.js");
const fs = require("node:fs");
const path = require("node:path");
const selected = {requestId: "request-1", state: "SELECTED", ticketId: "ticket-1", expiresAt: "2099-01-01T00:00:00Z"};
const settle = () => new Promise(resolve => setImmediate(resolve));

test("非安全上下文缺少 randomUUID 时使用 getRandomValues 生成 UUID v4", () => {
    const id = projects.createRequestId({getRandomValues(bytes) {
        for (let index = 0; index < bytes.length; index++) bytes[index] = index;
        return bytes;
    }});
    assert.equal(id, "00010203-0405-4607-8809-0a0b0c0d0e0f");
    assert.equal(projects.createRequestId({randomUUID: () => "preferred"}), "preferred");
    assert.throws(() => projects.createRequestId({}), /HTTPS/);
});

test("系统选择确认后才消费票据，双击不重复打开或创建", async () => {
    const calls = [];
    const flow = projects.createSelection({randomId: () => "request-1", request: async (url, options) => {
        calls.push([url, options]); return options?.method === "POST" && url === "/api/projects" ? {id: "project-1"} : selected;
    }});
    await Promise.all([flow.start(), flow.start()]);
    assert.equal(calls.filter(([url, options]) => options?.method === "POST" && url.includes("selections")).length, 1);
    assert.equal(flow.value().phase, "SELECTED");
    const result = await flow.create(" 我的项目 ");
    assert.equal(result.id, "project-1");
    const body = JSON.parse(calls.at(-1)[1].body);
    assert.deepEqual(body, {requestId: "request-1", ticketId: "ticket-1", name: "我的项目"});
    await assert.rejects(flow.create("我的项目"), /先完成/);
});

test("取消不会保留票据也不会新增项目", async () => {
    let mutations = 0;
    const flow = projects.createSelection({randomId: () => "request-1", request: async (url, options) => {
        if (options?.method === "POST") mutations++;
        return {...selected, state: "CANCELLED"};
    }});
    await flow.start();
    assert.equal(flow.value().phase, "CANCELLED");
    assert.equal(flow.value().ticketId, undefined);
    await assert.rejects(flow.create("项目"), /先完成/);
    assert.equal(mutations, 1);
});

test("选择网络未知只查询原请求，不自动重开窗口", async () => {
    const calls = [];
    const flow = projects.createSelection({randomId: () => "request-1", request: async (url, options) => {
        calls.push([url, options]); if (options?.method === "POST") throw new Error("网络中断"); return selected;
    }});
    await flow.start(); await flow.start();
    assert.equal(flow.value().phase, "UNKNOWN");
    assert.equal(calls.length, 1);
    await flow.check(); assert.equal(flow.value().phase, "SELECTED");
    assert.equal(calls[1][0], "/api/projects/selections/request-1");
});

test("添加结果未知禁止再次消费授权，查询也不能退回已选择", async () => {
    const calls = [];
    const flow = projects.createSelection({randomId: () => "request-1", request: async (url, options) => {
        calls.push(url); if (url === "/api/projects") throw new Error("确认前断网"); return selected;
    }});
    await flow.start(); await assert.rejects(flow.create("项目"), /断网/);
    assert.equal(flow.value().phase, "CREATE_UNKNOWN");
    await flow.start(); await flow.check(); await assert.rejects(flow.create("项目"));
    assert.equal(calls.filter(url => url === "/api/projects").length, 1);
});

test("过期和非法到期时间不发起创建", async () => {
    for (const expiresAt of ["2000-01-01T00:00:00Z", "invalid"]) {
        let creates = 0;
        const flow = projects.createSelection({randomId: () => "request-1", request: async url => {
            if (url === "/api/projects") creates++;
            return {...selected, expiresAt};
        }});
        await flow.start(); await assert.rejects(flow.create("项目"), /过期/);
        assert.equal(creates, 0);
    }
});

test("迟到选择查询不能覆盖更新的取消结果", async () => {
    let resolveOld, query = 0;
    const flow = projects.createSelection({randomId: () => "request-1", request: async (url, options) => {
        if (options?.method) return {};
        if (++query === 1) return {...selected, state: "SELECTING"};
        if (query === 2) return new Promise(resolve => { resolveOld = resolve; });
        return {...selected, state: "CANCELLED"};
    }});
    await flow.start(); const old = flow.check(); await flow.check(); resolveOld(selected); await old;
    assert.equal(flow.value().phase, "CANCELLED");
});

function dom() {
    class Node {
        constructor() { this.children = []; this.events = {}; this.hidden = false; this.disabled = false; this.dataset = {}; this.value = ""; this.attributes = {};
            this.classList = {toggle() {}}; }
        set textContent(value) { this.content = value; this.children = []; }
        get textContent() { return this.content; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; this.content = ""; }
        addEventListener(name, callback) { this.events[name] = callback; }
        setAttribute(name, value) { this.attributes[name] = value; }
        focus(options) { this.focusCalls = (this.focusCalls || 0) + 1; this.focusOptions = options; }
        click() { return this.events.click?.(); }
    }
    const nodes = new Map();
    return {getElementById(id) { if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id); }, createElement() { return new Node(); }};
}
function host() { return {crypto: {randomUUID: () => "request-1"}, setTimeout() { return 1; }, clearTimeout() {}}; }

test("服务未开放时按钮保持关闭且不会探测文件或弹出选择器", async () => {
    const document = dom(), calls = [];
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async url => { calls.push(url); return {enabled: false}; }}});
    await page.mount();
    assert.deepEqual(calls, ["/api/projects/capabilities"]);
    assert.equal(document.getElementById("projectAddBtn").disabled, true);
    assert.equal(document.getElementById("projectChooseFolder").disabled, true);
    page.dispose();
});

test("项目切换丢弃旧详情，文本只作为字面内容，离页清理异步请求", async () => {
    const document = dom(), calls = []; let finishA, finishText, stamp = 1;
    const a = {id: "a", name: "项目A", state: "ACTIVE", version: 1}, b = {...a, id: "b", name: "项目B"};
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => stamp, api: {request: async (url, options) => {
        calls.push([url, options]);
        assert.equal(options.headers["X-CM-Agent-Local-Project"], "1");
        if (url.endsWith("capabilities")) return {enabled: true, conversations: false};
        if (url.includes("?limit=30")) return [a, b];
        if (url === "/api/projects/a") return new Promise(resolve => { finishA = resolve; });
        if (url === "/api/projects/b") return b;
        if (url.includes("/files?")) return {items: [{name: "index.html", path: "index.html", kind: "FILE", readable: true}]};
        if (url.includes("/text?")) return new Promise(resolve => { finishText = resolve; });
        throw new Error(url);
    }}});
    await page.mount();
    const list = document.getElementById("localProjectList");
    const first = list.children[0].click(), second = list.children[1].click();
    await second; finishA(a); await first;
    assert.equal(document.getElementById("projectTitle").textContent, "项目B");
    assert.equal(document.getElementById("projectManagement").hidden, false);
    const file = document.getElementById("projectFileList").children[0];
    const read = file.click();
    finishText({path: "index.html", sha256: "sha", content: "<script>PRIVATE_BODY</script>", offset: 0, nextOffset: 30, totalBytes: 30});
    await read;
    assert.equal(document.getElementById("projectPreviewContent").textContent, "<script>PRIVATE_BODY</script>");
    const again = file.click(); page.dispose(); stamp++;
    const pending = calls.at(-1)[1]; assert.equal(pending.signal.aborted, true);
    finishText({content: "LATE_BODY"}); await again;
    assert.equal(document.getElementById("projectPreviewContent").textContent, "");
});

test("项目选择后焦点回到同一项目按钮", async () => {
    const document = dom();
    const project = {id: "a", name: "项目A", state: "ACTIVE", version: 1};
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async (url) => {
        if (url.endsWith("capabilities")) return {enabled: true, conversations: false};
        if (url.includes("?limit=30")) return [project];
        if (url === "/api/projects/a") return project;
        if (url.includes("/files?")) return {items: []};
        throw new Error(url);
    }}});
    await page.mount();
    await document.getElementById("localProjectList").children[0].click();
    const selected = document.getElementById("localProjectList").children[0];
    assert.equal(selected.attributes["aria-pressed"], "true");
    assert.ok(selected.focusCalls >= 1);
    assert.deepEqual(selected.focusOptions, {preventScroll: true});
    page.dispose();
});

test("v2入口均预载项目脚本样式且不将文件夹选择伪装为上传", () => {
    const base = path.join(__dirname, "../../main/resources/META-INF/resources/console/v2");
    for (const name of fs.readdirSync(base).filter(name => name.endsWith(".html"))) {
        const html = fs.readFileSync(path.join(base, name), "utf8");
        if (name === "local-client.html") {
            assert.doesNotMatch(html, /local-projects\.js\?v=1\.2\.0/);
            assert.doesNotMatch(html, /project-development\.js\?v=1\.1\.4/);
        } else {
            assert.match(html, /local-projects\.js\?v=1\.2\.0/);
            assert.match(html, /project-development\.js\?v=1\.1\.4/);
        }
        assert.match(html, /local-projects\.css\?v=1\.6\.2/);
        if (name !== "login.html") assert.match(html, /href="\/console\/v2\/projects\.html"/);
    }
    const html = fs.readFileSync(path.join(base, "projects.html"), "utf8");
    assert.doesNotMatch(html, /webkitdirectory|type="file"/);
    assert.match(html, /运行 CM Agent Server 的电脑/);
    assert.match(html, /id="projectToolCatalogHelp"/);
    for (const tool of ["project_list_files", "project_read_file", "project_search_files", "project_propose_changes",
        "project_apply_changes", "project_propose_undo", "project_list_toolchains", "project_propose_command",
        "project_run_command", "project_command_status", "project_cancel_command"]) assert.match(html, new RegExp(tool));
});

async function archiveFlow(failure = "") {
    const document = dom(), calls = []; let archived = false, finish;
    const project = {id: "a", name: "原项目", state: "ACTIVE", version: 3};
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async (url, options) => {
        calls.push([url, options]);
        if (url.endsWith("capabilities")) return {enabled: true, conversations: false};
        if (url.includes("?limit=30")) return archived ? [] : [project];
        if (url === "/api/projects/a") return project;
        if (url.includes("/files?")) return {items: [{name: "readme.txt", path: "readme.txt", kind: "FILE", readable: true}]};
        if (url.includes("/text?")) return {path: "readme.txt", content: "PRIVATE_PREVIEW_BODY", sha256: "a".repeat(64), offset: 0, nextOffset: 20};
        if (url.endsWith("/archive")) {
            if (failure === "conflict") throw Object.assign(new Error("仍有待审批运行，请先停止"), {status: 409});
            if (failure === "unknown") throw new Error("网络中断");
            return new Promise(resolve => { finish = () => { archived = true; resolve({...project, state: "ARCHIVED", version: 4}); }; });
        }
        throw new Error(url);
    }}});
    await page.mount(); await document.getElementById("localProjectList").children[0].click();
    await document.getElementById("projectFileList").children[0].click();
    return {page, document, calls, finish: () => finish()};
}
test("归档只提交项目版本且双击不重发，成功清空预览并刷新活动列表", async () => {
    const flow = await archiveFlow(), button = flow.document.getElementById("projectArchiveBtn");
    const pending = button.click(); await button.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/archive")).length, 1);
    assert.deepEqual(JSON.parse(flow.calls.at(-1)[1].body), {expectedVersion: 3});
    flow.finish(); await pending;
    assert.equal(flow.document.getElementById("projectDetailRegion").hidden, true);
    assert.equal(flow.document.getElementById("projectPreviewContent").textContent, "");
    assert.equal(flow.document.getElementById("projectFileList").children.length, 0);
    assert.match(flow.document.getElementById("projectDetailEmpty").textContent, /本地目录与历史保留/);
    assert.equal(flow.document.getElementById("projectListHeading").focusCalls, 1);
    flow.page.dispose();
});
test("归档409保留项目和文件上下文，不自动重试或清空正文", async () => {
    const flow = await archiveFlow("conflict");
    await flow.document.getElementById("projectArchiveBtn").click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/archive")).length, 1);
    assert.equal(flow.document.getElementById("projectDetailRegion").hidden, false);
    assert.equal(flow.document.getElementById("projectPreviewContent").textContent, "PRIVATE_PREVIEW_BODY");
    assert.equal(flow.document.getElementById("projectArchiveBtn").disabled, false);
    assert.match(flow.document.getElementById("projectDetailStatus").textContent, /请先停止/);
    flow.page.dispose();
});

test("归档网络未知保留锁定并提示核对，手动再次点击也不会重放", async () => {
    const flow = await archiveFlow("unknown"), button = flow.document.getElementById("projectArchiveBtn");
    await button.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/archive")).length, 1);
    assert.equal(button.disabled, true);
    assert.match(flow.document.getElementById("projectDetailStatus").textContent, /结果尚未确认/);
    await button.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/archive")).length, 1);
    flow.page.dispose();
});

test("改名版本冲突保留当前项目并提示重新核对，不自动重试", async () => {
    const document = dom(), calls = [];
    const project = {id: "a", name: "原项目", state: "ACTIVE", version: 3};
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async (url, options) => {
        calls.push([url, options]);
        if (url.endsWith("capabilities")) return {enabled: true, conversations: false};
        if (url.includes("?limit=30")) return [project];
        if (url === "/api/projects/a" && options?.method === "PATCH")
            throw Object.assign(new Error("项目已变化"), {status: 409});
        if (url === "/api/projects/a") return project;
        if (url.includes("/files?")) return {items: []};
        throw new Error(url);
    }}});
    await page.mount();
    await document.getElementById("localProjectList").children[0].click();
    document.getElementById("projectRenameInput").value = "新名字";
    await document.getElementById("projectRenameForm").events.submit({preventDefault() {}});
    assert.equal(calls.filter(([, options]) => options?.method === "PATCH").length, 1);
    assert.equal(document.getElementById("projectTitle").textContent, "原项目");
    assert.equal(document.getElementById("projectRenameBtn").disabled, false);
    assert.match(document.getElementById("projectDetailStatus").textContent, /重新选择项目核对/);
    page.dispose();
});

function installHarness(request) {
    const document = dom(), calls = [];
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async (url, options) => {
        calls.push([url, options]);
        if (url.endsWith("capabilities")) return {enabled: true, conversations: true};
        if (url.includes("/api/projects?limit=30")) return [];
        if (url === "/api/projects/tools" && options?.method === "POST") return request(url, options, calls);
        throw new Error(url);
    }}});
    return {document, calls, page};
}

test("固定工具目录登记双击只提交一次，成功后保持已登记状态", async () => {
    const flow = installHarness(async () => {
        await new Promise(resolve => setImmediate(resolve));
        return [];
    });
    await flow.page.mount();
    const button = flow.document.getElementById("projectInstallTools");
    const pending = button.click();
    await button.click();
    await pending;
    assert.equal(flow.calls.filter(([url, options]) => url === "/api/projects/tools" && options?.method === "POST").length, 1);
    assert.equal(button.disabled, true);
    assert.equal(button.textContent, "固定项目工具目录已登记");
    assert.match(flow.document.getElementById("localProjectPageStatus").textContent, /仍只为所选 Agent 补齐缺失授权/);
    flow.page.dispose();
});

test("固定工具目录登记受控失败可重试，网络未知保持锁定直到刷新", async () => {
    let controlledCalls = 0;
    const controlled = installHarness(async () => {
        controlledCalls++;
        throw Object.assign(new Error("缺少工具授权权限"), {status: 403});
    });
    await controlled.page.mount();
    const controlledButton = controlled.document.getElementById("projectInstallTools");
    await controlledButton.click();
    assert.equal(controlledButton.disabled, false);
    assert.match(controlled.document.getElementById("localProjectPageStatus").textContent, /缺少工具授权权限/);
    await controlledButton.click();
    assert.equal(controlledCalls, 2);
    controlled.page.dispose();

    let unknownCalls = 0;
    const unknown = installHarness(async () => {
        unknownCalls++;
        throw new Error("网络中断");
    });
    await unknown.page.mount();
    const unknownButton = unknown.document.getElementById("projectInstallTools");
    await unknownButton.click();
    assert.equal(unknownButton.disabled, true);
    assert.match(unknown.document.getElementById("localProjectPageStatus").textContent, /尚未确认/);
    await unknownButton.click();
    assert.equal(unknownCalls, 1);
    unknown.page.dispose();
});
test("会话与文件切换保持项目，引用成功切回输入且不自动发送", async () => {
    const document = dom(), browser = host(), calls = [];
    browser.CmAgentProjectDevelopment = {create() { return {async mount() {}, dispose() {}, referenceFile(value) { document.getElementById("projectChatInput").value = JSON.stringify(value); }}; }};
    const page = projects.createProjectPage({document, host: browser, getSessionEpoch: () => 1, api: {request: async url => {
        calls.push(url);
        if (url.endsWith("capabilities")) return {enabled: true, conversations: true};
        if (url.includes("?limit=30")) return [{id: "project", name: "测试", state: "ACTIVE", version: 1}];
        if (url === "/api/projects/project") return {id: "project", name: "测试", state: "ACTIVE", version: 1};
        if (url.includes("/files?")) return {items: [{kind: "FILE", readable: true, name: "readme.txt", path: "readme.txt"}]};
        if (url.includes("/text?")) return {path: "readme.txt", sha256: "a".repeat(64), offset: 0, nextOffset: 12, content: "PRIVATE_BODY"};
        throw new Error(url);
    }}});
    await page.mount(); await document.getElementById("localProjectList").children[0].click();
    assert.equal(document.getElementById("projectWorkingGrid").dataset.activePane, "chat");
    await document.getElementById("projectPaneFiles").click();
    assert.equal(document.getElementById("projectWorkingGrid").dataset.activePane, "files");
    await document.getElementById("projectFileList").children[0].click(); const count = calls.length;
    await document.getElementById("projectReferenceFile").click();
    assert.equal(document.getElementById("projectWorkingGrid").dataset.activePane, "chat");
    assert.doesNotMatch(document.getElementById("projectChatInput").value, /PRIVATE_BODY/); assert.equal(calls.length, count);
    page.dispose();
});


test("归档列表默认关闭，切换过滤重新从首项读取而不携带旧游标", async () => {
    const document = dom(), calls = [];
    const items = Array.from({length: 30}, (_, index) => ({id: `project-${index}`, name: `项目${index}`, state: "ACTIVE", updatedAt: "2026-10-30T00:00:00Z"}));
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async url => {
        calls.push(url); if (url.endsWith("capabilities")) return {enabled: true}; return items;
    }}});
    await page.mount();
    assert.equal(new URL(calls.at(-1), "http://local").searchParams.get("includeArchived"), "false");
    await document.getElementById("projectLoadMore").click();
    assert.equal(new URL(calls.at(-1), "http://local").searchParams.get("beforeId"), "project-29");
    const filter = document.getElementById("projectIncludeArchived"); filter.checked = true; await filter.events.change();
    const query = new URL(calls.at(-1), "http://local").searchParams;
    assert.equal(query.get("includeArchived"), "true"); assert.equal(query.has("beforeId"), false);
    assert.equal(query.has("beforeUpdatedAt"), false); page.dispose();
});

test("选择归档项目仅展示历史说明，旧文件与更名归档入口不能访问原目录或写入", async () => {
    const document = dom(), calls = [], project = {id: "archived", name: "归档项目", state: "ARCHIVED", version: 2};
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async url => {
        calls.push(url); if (url.endsWith("capabilities")) return {enabled: true};
        if (url.includes("?limit=30")) return [project]; if (url === "/api/projects/archived") return project;
        throw new Error(`不应请求：${url}`);
    }}});
    await page.mount(); await document.getElementById("localProjectList").children[0].click();
    assert.match(document.getElementById("projectTreeStatus").textContent, /仅查看会话、审批历史和操作记录/);
    assert.equal(document.getElementById("projectManagement").hidden, true);
    assert.equal(document.getElementById("projectRenameDetails").hidden, true);
    assert.equal(document.getElementById("projectArchiveControls").hidden, true);
    assert.equal(document.getElementById("projectTreeRefresh").disabled, true);
    const count = calls.length;
    await document.getElementById("projectTreeRefresh").click(); await document.getElementById("projectArchiveBtn").click();
    await document.getElementById("projectRenameForm").events.submit({preventDefault() {}});
    await document.getElementById("projectReferenceFile").click();
    await document.getElementById("projectSearchForm").events.submit({preventDefault() {}});
    assert.equal(document.getElementById("projectSearchQuery").disabled, true);
    assert.equal(calls.length, count); assert.equal(calls.some(url => /\/(files|text)\?/.test(url)), false); page.dispose();
});


test("搜索显示受限与覆盖预算提示，旧项目迟到结果丢弃，命中仅预览当前项目且不带目录游标", async () => {
    const document = dom(), calls = [], a = {id: "a", name: "A", state: "ACTIVE"}, b = {...a, id: "b"}; let finishOld;
    const page = projects.createProjectPage({document, host: host(), getSessionEpoch: () => 1, api: {request: async url => {
        calls.push(url); if (url.endsWith("capabilities")) return {enabled: true};
        if (url.includes("?limit=30")) return [a,b]; if (url === "/api/projects/a") return a; if (url === "/api/projects/b") return b;
        if (url.includes("/files?")) return {items: [], nextCursor: "旧目录游标"};
        if (url.includes("/a/search?")) return new Promise(resolve => { finishOld = resolve; });
        if (url.includes("/b/search?")) return {matches: [{path: "README.md", contentMatch: true, excerpt: "<script>仅字面片段</script>"}], partial: true, scanned: 80, skipped: 4};
        if (url.includes("/b/text?")) return {path: "README.md", content: "当前文件", sha256: "a".repeat(64), offset: 0, nextOffset: 12, totalBytes: 12};
        throw new Error(url);
    }}});
    await page.mount(); await document.getElementById("localProjectList").children[0].click();
    document.getElementById("projectSearchQuery").value = "README";
    const old = document.getElementById("projectSearchForm").events.submit({preventDefault() {}});
    await document.getElementById("localProjectList").children[1].click();
    finishOld({matches: [{path: "PRIVATE_OLD"}], partial: false, scanned: 1, skipped: 0}); await old;
    assert.equal(document.getElementById("projectSearchResults").children.length, 0);
    document.getElementById("projectSearchQuery").value = "README"; document.getElementById("projectSearchContent").checked = true;
    await document.getElementById("projectSearchForm").events.submit({preventDefault() {}});
    assert.match(document.getElementById("projectSearchStatus").textContent, /跳过 4 项/);
    assert.match(document.getElementById("projectSearchStatus").textContent, /结果不完整/);
    const query = new URL(calls.at(-1), "http://local").searchParams;
    assert.equal(query.get("content"), "true"); assert.equal(query.has("cursor"), false);
    assert.equal(document.getElementById("projectSearchResults").children[1].textContent, "<script>仅字面片段</script>");
    await document.getElementById("projectSearchResults").children[0].click();
    assert.match(calls.at(-1), /\/b\/text\?/); assert.equal(document.getElementById("projectReferenceFile").disabled, false); page.dispose();
});
