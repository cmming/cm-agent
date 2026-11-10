const test = require("node:test");
const assert = require("node:assert/strict");
const projects = require("../../main/resources/META-INF/resources/console/v2/assets/client-projects.js");

test("非安全上下文仍生成 UUID v4 请求编号", () => {
    const id = projects.createRequestId({getRandomValues(bytes) {
        for (let index = 0; index < bytes.length; index++) bytes[index] = index;
        return bytes;
    }});
    assert.equal(id, "00010203-0405-4607-8809-0a0b0c0d0e0f");
    assert.equal(projects.createRequestId({randomUUID: () => "preferred"}), "preferred");
    assert.throws(() => projects.createRequestId({}), /HTTPS/);
});

test("设备任务只轮询原请求直到成功", async () => {
    const calls = [];
    const task = {requestId: "request-1", expiresAt: "2099-01-01T00:00:00Z"};
    const result = await projects.waitForTask({
        task,
        request: async path => {
            calls.push(path);
            return calls.length === 1
                ? {requestId: "request-1", state: "DISPATCHED"}
                : {requestId: "request-1", state: "SUCCEEDED", output: {items: []}};
        },
        statusPath: id => `/status/${id}`,
        host: {setTimeout(resolve) { resolve(); }}
    });
    assert.deepEqual(calls, ["/status/request-1", "/status/request-1"]);
    assert.deepEqual(result.output, {items: []});
});

test("设备任务未知或失效时明确失败且不重发", async () => {
    let calls = 0;
    await assert.rejects(projects.waitForTask({
        task: {requestId: "request-1", expiresAt: "2099-01-01T00:00:00Z"},
        request: async () => {
            calls++;
            return {requestId: "request-1", state: "UNKNOWN", error: "结果未知"};
        },
        statusPath: id => `/status/${id}`,
        host: {setTimeout(resolve) { resolve(); }}
    }), /结果未知/);
    assert.equal(calls, 1);
});

test("文件变更只有版本摘要完整且脱敏不掩盖差异时才可允许", () => {
    const reference = {operationId: "00000000-0000-4000-8000-000000000001",
        version: 1, digest: "a".repeat(64)};
    const preview = {...reference, displayExact: true,
        changes: [{kind: "REPLACE_FILE", path: "README.md", before: "旧", after: "新"}]};
    const development = {matchesPreview: (ref, value) => ref.digest === value.digest,
        completePreview: value => value.changes?.length === 1};
    assert.equal(projects.canApproveChange(reference, preview, development), true);
    assert.equal(projects.canApproveChange(reference,
        {...preview, displayExact: false}, development), false);
    assert.equal(projects.canApproveChange(reference,
        {...preview, digest: "b".repeat(64)}, development), false);
    assert.equal(projects.canApproveChange(reference,
        {...preview, changes: []}, development), false);
});

test("固定工具链命令只在完整精确预览和同一提案时可允许", () => {
    const reference = {operationId: "00000000-0000-4000-8000-000000000002",
        version: 2, digest: "b".repeat(64)};
    const command = {commandId: reference.operationId, version: 2,
        planDigest: reference.digest, displayExact: true, preview: {
            toolchainId: "java", displayName: "Java", executable: "java.exe",
            executableHash: "c".repeat(64), prefixArguments: ["-version"], arguments: [],
            relativeDirectory: "", environmentKeys: ["PATH"],
            environmentOverrides: {},
            environmentDigest: "d".repeat(64), buildFiles: ["pom.xml"],
            timeoutSeconds: 60, maxOutputBytes: 65536
        }};
    const development = {matchesPreview: (ref, value) => ref.digest === value.digest};
    assert.equal(projects.canApproveCommand(reference, command, development), true);
    assert.equal(projects.canApproveCommand(reference,
        {...command, displayExact: false}, development), false);
    assert.equal(projects.canApproveCommand(reference,
        {...command, preview: {...command.preview, executableHash: "invalid"}}, development), false);
});

test("能力关闭时只探测固定入口且不加载设备或文件", async () => {
    const document = dom();
    const calls = [];
    const page = projects.createClientProjectPage({
        document, host: host(), getSessionEpoch: () => 1,
        api: {request: async (url, options) => {
            calls.push([url, options]);
            return {enabled: false};
        }}
    });
    await page.mount();
    assert.deepEqual(calls.map(([url]) => url), ["/api/client-projects/capabilities"]);
    assert.equal(calls[0][1].headers["X-CM-Agent-Client-Project"], "1");
    assert.equal(document.getElementById("projectSourceClient").hidden, true);
    assert.equal(document.getElementById("clientProjectAddBtn").disabled, true);
    page.dispose();
});

test("设备选择等待时展示同一请求编号供本机核对", async () => {
    const previousOption = global.Option;
    global.Option = class { constructor(label, value) { this.label = label; this.value = value; } };
    const document = dom();
    let finishStatus;
    const page = projects.createClientProjectPage({document, host: host(), getSessionEpoch: () => 1,
        api: {request: async (url) => {
            if (url.endsWith("/capabilities")) return {enabled: true};
            if (url === "/api/local-client/devices") return [{deviceId: "device", state: "ACTIVE"}];
            if (url.startsWith("/api/client-projects?")) return [];
            if (url === "/api/agents") return [];
            if (url.endsWith("/selections")) return {requestId: "request-1", expiresAt: "2099-01-01T00:00:00Z"};
            if (url.includes("/selections/")) return new Promise(resolve => { finishStatus = resolve; });
            return {items: []};
        }}});
    try {
        await page.mount();
        document.getElementById("clientProjectDevice").value = "device";
        const pending = document.getElementById("clientProjectAddBtn").events.click();
        for (let i = 0; i < 5 && !finishStatus; i++) await Promise.resolve();
        assert.match(document.getElementById("clientProjectPageStatus").textContent, /request-1/);
        finishStatus({requestId: "request-1", state: "FAILED", error: "取消"});
        await pending;
    } finally {
        page.dispose();
        global.Option = previousOption;
    }
});

test("设备项目经通用消息审批和Run路由时仍携带专用行动头", async () => {
    const previousOption = global.Option;
    global.Option = class { constructor(label, value) { this.label = label; this.value = value; } };
    const document = dom(), calls = [];
    const conversation = {id: "conversation", agentId: "agent", title: "验收"};
    const page = projects.createClientProjectPage({document, host: host(), getSessionEpoch: () => 1,
        api: {request: async (url, options) => {
            calls.push([url, options]);
            if (url.endsWith("/capabilities")) return {enabled: true};
            if (url === "/api/local-client/devices") return [{deviceId: "device", state: "ACTIVE"}];
            if (url.startsWith("/api/client-projects?")) return [{id: "project", deviceId: "device", state: "ACTIVE"}];
            if (url === "/api/agents") return [{id: "agent", name: "验收", enabled: true}];
            if (url.includes("/conversations?")) return [conversation];
            if (url.endsWith("/queries")) return {requestId: "query", expiresAt: "2099-01-01T00:00:00Z"};
            if (url.includes("/queries/")) return {requestId: "query", state: "SUCCEEDED", output: {entries: []}};
            if (url.endsWith("/active-run")) return null;
            if (url.endsWith("/messages") && options.method === "POST") return {run: {id: "run", status: "SUCCEEDED", finishedAt: "2026-11-10T00:00:00Z"}};
            return {items: []};
        }}});
    try {
        await page.mount();
        await document.getElementById("clientProjectList").children[0].events.click();
        await document.getElementById("clientProjectConversationList").children[0].events.click();
        document.getElementById("clientProjectChatInput").value = "验收输入";
        await document.getElementById("clientProjectChatForm").events.submit({preventDefault() {}});
        const derived = calls.filter(([url]) => url.startsWith("/api/agents/agent/conversations/"));
        assert.ok(derived.some(([url, options]) => url.endsWith("/messages") && options.method === "POST"));
        assert.ok(derived.some(([url]) => url.endsWith("/approvals")));
        assert.ok(derived.some(([url]) => url.endsWith("/active-run")));
        for (const [, options] of derived) assert.equal(options.headers["X-CM-Agent-Client-Project"], "1");
        assert.equal(document.getElementById("clientProjectChatSend").disabled, false);
    } finally {
        page.dispose();
        if (previousOption === undefined) delete global.Option;
        else global.Option = previousOption;
    }
});

test("精确拒绝使用真实SSE审批路由且断流错误保留编号并禁止重提", async () => {
    const previousOption = global.Option;
    global.Option = class { constructor(label, value) { this.label = label; this.value = value; } };
    try {
        for (const failure of [false, true]) {
            const document = dom(), streams = [], scheduled = [];
            let pending = true;
            const approval = {approvalId: "approval", version: 3, status: "PENDING", canDecide: true,
                items: [{itemId: "item", toolName: "client_project_run_command", inputSummary: "精确操作"}]};
            const page = projects.createClientProjectPage({document, getSessionEpoch: () => 1,
                host: {...host(), setTimeout(callback) { scheduled.push(callback); return scheduled.length; },
                    CmAgentProjectDevelopment: {operationReference: () => ({operationId: "command"})}},
                api: {request: async (url) => {
                    if (url.endsWith("/capabilities")) return {enabled: true};
                    if (url === "/api/local-client/devices") return [{deviceId: "device", state: "ACTIVE"}];
                    if (url.startsWith("/api/client-projects?")) return [{id: "project", deviceId: "device", state: "ACTIVE"}];
                    if (url === "/api/agents") return [{id: "agent", name: "验收", enabled: true}];
                    if (url.includes("/conversations?")) return [{id: "conversation", agentId: "agent"}];
                    if (url.endsWith("/queries")) return {requestId: "query", expiresAt: "2099-01-01T00:00:00Z"};
                    if (url.includes("/queries/")) return {requestId: "query", state: "SUCCEEDED", output: {entries: []}};
                    if (url.endsWith("/active-run")) return null;
                    if (url.endsWith("/approvals")) return {items: pending ? [approval] : []};
                    return {items: []};
                }, stream: async (url, options, onEvent) => {
                    streams.push([url, options]);
                    onEvent({type: "approval-decision", data: {status: "DENIED"}});
                    if (failure) onEvent({type: "error", data: {code: "RUNTIME_ERROR", errorId: "fixture-error", message: "受控失败"}});
                    pending = false;
                    onEvent({type: "completed", data: {run: {status: "DENIED"}}});
                }}});
            try {
                await page.mount();
                await document.getElementById("clientProjectList").children[0].events.click();
                await document.getElementById("clientProjectConversationList").children[0].events.click();
                const card = document.getElementById("clientProjectApprovalItems").children[0];
                const select = card.children[1].children.at(-1);
                select.value = "DENY"; select.events.change();
                await scheduled.shift()();
                assert.equal(document.getElementById("clientProjectApprovalItems").children[0], card);
                assert.equal(select.value, "DENY");
                await card.children.at(-1).events.click();
                assert.equal(streams.length, 1);
                assert.equal(streams[0][0], "/api/agents/agent/conversations/conversation/approvals/approval/decision/stream");
                assert.equal(streams[0][1].headers["X-CM-Agent-Client-Project"], "1");
                assert.deepEqual(JSON.parse(streams[0][1].body), {expectedVersion: 3, decisions: [{itemId: "item", decision: "DENY"}]});
                const message = document.getElementById("clientProjectChatStatus").textContent;
                assert.match(message, failure ? /受控失败.*RUNTIME_ERROR.*fixture-error.*不能再次提交/ : /DENIED/);
                if (failure) assert.equal(card.children.at(-1).disabled, true);
            } finally { page.dispose(); }
        }
    } finally {
        if (previousOption === undefined) delete global.Option;
        else global.Option = previousOption;
    }
});

function dom() {
    class Node {
        constructor() {
            this.children = []; this.events = {}; this.hidden = false; this.disabled = false;
            this.dataset = {}; this.value = ""; this.checked = false; this.className = "";
            this.attributes = {}; this.classList = {toggle() {}};
        }
        set textContent(value) { this.content = value; this.children = []; }
        get textContent() { return this.content || ""; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; this.content = ""; }
        addEventListener(name, callback) { this.events[name] = callback; }
        setAttribute(name, value) { this.attributes[name] = value; }
        focus() {}
    }
    const nodes = new Map();
    return {
        getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, new Node());
            return nodes.get(id);
        },
        createElement() { return new Node(); }
    };
}

function host() {
    return {crypto: {randomUUID: () => "request-1"},
        setTimeout(resolve) { resolve(); }, clearTimeout() {}};
}
